package net.minecraftforge.gradle.tasks;

import com.github.abrarsyed.jastyle.ASFormatter;
import com.github.abrarsyed.jastyle.FileWildcardFilter;
import com.github.abrarsyed.jastyle.OptParser;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import groovy.lang.Closure;
import net.minecraftforge.gradle.FileUtils;
import net.minecraftforge.gradle.JavaExecSpecHelper;
import net.minecraftforge.gradle.common.BaseExtension;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.extrastuff.FFPatcher;
import net.minecraftforge.gradle.extrastuff.FmlCleanup;
import net.minecraftforge.gradle.extrastuff.GLConstantFixer;
import net.minecraftforge.gradle.extrastuff.McpCleanup;
import net.minecraftforge.gradle.patching.ContextualPatch;
import net.minecraftforge.gradle.patching.ContextualPatch.HunkReport;
import net.minecraftforge.gradle.patching.ContextualPatch.PatchReport;
import net.minecraftforge.gradle.patching.ContextualPatch.PatchStatus;
import net.minecraftforge.gradle.tasks.abstractutil.CachedTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.*;
import org.gradle.process.JavaExecSpec;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static net.minecraftforge.gradle.common.Constants.EXT_NAME_MC;

public class DecompileTask extends CachedTask {
    @InputFile
    private DelayedFile inJar;

    @InputFile
    private DelayedFile fernFlower;

    private DelayedFile patch;

    @InputFile
    private DelayedFile astyleConfig;

    @OutputFile
    @Cached
    private DelayedFile outJar;

    private HashMap<String, String> sourceMap = new HashMap<>();
    private HashMap<String, byte[]> resourceMap = new HashMap<>();

    private static final Pattern BEFORE = Pattern.compile("(?m)((case|default).+(?:\\r\\n|\\r|\\n))(?:\\r\\n|\\r|\\n)");
    private static final Pattern AFTER = Pattern.compile("(?m)(?:\\r\\n|\\r|\\n)((?:\\r\\n|\\r|\\n)[ \\t]+(case|default))");

    /**
     * This method outputs to the cleanSrc
     *
     * @throws Throwable Let em throw anything.. I dont care.
     */
    @TaskAction
    protected void doMCPStuff() throws Throwable {
        // define files.
        File temp = new File(getTemporaryDir(), getInJar().getName());

        getLogger().info("Decompiling Jar");
        decompile(getInJar(), getTemporaryDir(), getFernFlower());

        getLogger().info("Loading decompiled jar");
        readJarAndFix(temp);

        saveJar(new File(getTemporaryDir(), getInJar().getName() + ".fixed.jar"));

        getLogger().info("Applying MCP patches");
        if (getPatch().isFile()) {
            applySingleMcpPatch(getPatch());
        } else {
            applyPatchDirectory(getPatch());
        }

        saveJar(new File(getTemporaryDir(), getInJar().getName() + ".patched.jar"));

        getLogger().info("Cleaning source");
        applyMcpCleanup(getAstyleConfig());

        getLogger().info("Saving Jar");
        saveJar(getOutJar());
    }

    private void decompile(final File inJar, final File outJar, final File fernFlower) {
        getProject().javaexec(new Closure<JavaExecSpec>(this) {
            private static final long serialVersionUID = 4608694547855396167L;

            public JavaExecSpec call() {
                JavaExecSpec exec = (JavaExecSpec) getDelegate();

                exec.args(
                        fernFlower.getAbsolutePath(),
                        "-din=1",
                        "-rbr=0",
                        "-dgs=1",
                        "-asc=1",
                        "-log=ERROR",
                        inJar.getAbsolutePath(),
                        outJar.getAbsolutePath()
                );

                JavaExecSpecHelper.setMainClass(exec, "-jar");
                exec.setWorkingDir(fernFlower.getParentFile());

                exec.classpath(Constants.getClassPath());
                exec.setStandardOutput(Constants.getTaskLogStream(getProject(), getName() + ".log"));

                exec.setMaxHeapSize("512M");

                return exec;
            }

            public JavaExecSpec call(Object obj) {
                return call();
            }
        });
    }

    private void readJarAndFix(final File jar) throws IOException {
        // begin reading jar
        final ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar.toPath()));
        ZipEntry entry;
        String fileStr;

        BaseExtension exten = (BaseExtension) getProject().getExtensions().getByName(EXT_NAME_MC);
        boolean fixInterfaces = !exten.getVersion().equals("1.7.2");

        while ((entry = zin.getNextEntry()) != null) {
            // no META or dirs. wel take care of dirs later.
            if (entry.getName().contains("META-INF")) {
                continue;
            }

            // resources or directories.
            if (entry.isDirectory() || !entry.getName().endsWith(".java")) {
                resourceMap.put(entry.getName(), ByteStreams.toByteArray(zin));
            } else {
                // source!
                fileStr = new String(ByteStreams.toByteArray(zin), Charset.defaultCharset());

                // fix
                fileStr = FFPatcher.processFile(new File(entry.getName()).getName(), fileStr, fixInterfaces);

                sourceMap.put(entry.getName(), fileStr);
            }
        }

        zin.close();
    }

    private void applySingleMcpPatch(File patchFile) throws Throwable {
        ContextualPatch patch = ContextualPatch.create(FileUtils.readString(patchFile, Charset.defaultCharset()), new ContextProvider(sourceMap));
        printPatchErrors(patch.patch(false));
    }

    private void printPatchErrors(List<PatchReport> errors) throws Throwable {
        boolean fuzzed = false;
        for (PatchReport report : errors) {
            if (!report.getStatus().isSuccess()) {
                getLogger().log(LogLevel.ERROR, "Patching failed: " + report.getTarget(), report.getFailure());

                for (HunkReport hunk : report.getHunks()) {
                    if (!hunk.getStatus().isSuccess()) {
                        getLogger().error("Hunk " + hunk.getHunkID() + " failed!");
                    }
                }

                throw report.getFailure();
            } else if (report.getStatus() == PatchStatus.Fuzzed) // catch fuzzed patches
            {
                getLogger().log(LogLevel.INFO, "Patching fuzzed: " + report.getTarget(), report.getFailure());
                fuzzed = true;

                for (HunkReport hunk : report.getHunks()) {
                    if (!hunk.getStatus().isSuccess()) {
                        getLogger().info("Hunk " + hunk.getHunkID() + " fuzzed " + hunk.getFuzz() + "!");
                    }
                }
            } else {
                getLogger().debug("Patch succeeded: " + report.getTarget());
            }
        }
        if (fuzzed)
            getLogger().lifecycle("Patches Fuzzed!");
    }

    private void applyPatchDirectory(File patchDir) throws Throwable {
        Multimap<String, File> patches = ArrayListMultimap.create();
        for (File f : patchDir.listFiles(new FileWildcardFilter("*.patch"))) {
            String base = f.getName();
            patches.put(base, f);
            for (File e : patchDir.listFiles(new FileWildcardFilter(base + ".*"))) {
                patches.put(base, e);
            }
        }

        for (String key : patches.keySet()) {
            ContextualPatch patch = findPatch(patches.get(key));
            if (patch == null) {
                getLogger().lifecycle("Patch not found for set: " + key); //This should never happen, but whatever
            } else {
                printPatchErrors(patch.patch(false));
            }
        }
    }

    private ContextualPatch findPatch(Collection<File> files) throws Throwable {
        ContextualPatch patch = null;
        for (File f : files) {
            patch = ContextualPatch.create(FileUtils.readString(f, Charset.defaultCharset()), new ContextProvider(sourceMap));
            List<PatchReport> errors = patch.patch(true);

            boolean success = true;
            for (PatchReport rep : errors) {
                if (!rep.getStatus().isSuccess()) {
                    success = false;
                    break;
                }
            }
            if (success) break;
        }
        return patch;
    }

    private void applyMcpCleanup(File conf) throws IOException {
        ASFormatter formatter = new ASFormatter();
        OptParser parser = new OptParser(formatter);
        parser.parseOptionFile(conf);

        Reader reader;
        Writer writer;

        GLConstantFixer fixer = new GLConstantFixer();
        ArrayList<String> files = new ArrayList<>(sourceMap.keySet());
        Collections.sort(files); // Just to make sure we have the same order.. shouldn't matter on anything but lets be careful.

        for (String file : files) {
            String text = sourceMap.get(file);

            getLogger().debug("Processing file: " + file);

            getLogger().debug("processing comments");
            text = McpCleanup.stripComments(text);

            getLogger().debug("fixing imports comments");
            text = McpCleanup.fixImports(text);

            getLogger().debug("various other cleanup");
            text = McpCleanup.cleanup(text);

            getLogger().debug("fixing OGL constants");
            text = fixer.fixOGL(text);

            getLogger().debug("formatting source");
            reader = new StringReader(text);
            writer = new StringWriter();
            formatter.format(reader, writer);
            reader.close();
            writer.flush();
            writer.close();
            text = writer.toString();

            getLogger().debug("applying FML transformations");
            text = BEFORE.matcher(text).replaceAll("$1");
            text = AFTER.matcher(text).replaceAll("$1");
            text = FmlCleanup.renameClass(text);

            sourceMap.put(file, text);
        }
    }

    private void saveJar(File output) throws IOException {
        ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(output.toPath()));

        // write in resources
        for (Map.Entry<String, byte[]> entry : resourceMap.entrySet()) {
            zout.putNextEntry(new ZipEntry(entry.getKey()));
            zout.write(entry.getValue());
            zout.closeEntry();
        }

        // write in sources
        for (Map.Entry<String, String> entry : sourceMap.entrySet()) {
            zout.putNextEntry(new ZipEntry(entry.getKey()));
            zout.write(entry.getValue().getBytes());
            zout.closeEntry();
        }

        zout.close();
    }

    @Internal
    public HashMap<String, String> getSourceMap() {
        return sourceMap;
    }

    public void setSourceMap(HashMap<String, String> sourceMap) {
        this.sourceMap = sourceMap;
    }

    public File getAstyleConfig() {
        return astyleConfig.call();
    }

    public void setAstyleConfig(DelayedFile astyleConfig) {
        this.astyleConfig = astyleConfig;
    }

    public File getFernFlower() {
        return fernFlower.call();
    }

    public void setFernFlower(DelayedFile fernFlower) {
        this.fernFlower = fernFlower;
    }

    public File getInJar() {
        return inJar.call();
    }

    public void setInJar(DelayedFile inJar) {
        this.inJar = inJar;
    }

    public File getOutJar() {
        return outJar.call();
    }

    public void setOutJar(DelayedFile outJar) {
        this.outJar = outJar;
    }

    @InputFiles
    public FileCollection getPatches() {
        File patches = patch.call();
        if (patches.isDirectory())
            return getProject().fileTree(patches);
        else
            return getProject().files(patches);
    }

    @InputDirectory
    public File getPatch() {
        return patch.call();
    }

    public void setPatch(DelayedFile patch) {
        this.patch = patch;
    }

    @Internal
    public HashMap<String, byte[]> getResourceMap() {
        return resourceMap;
    }

    public void setResourceMap(HashMap<String, byte[]> resourceMap) {
        this.resourceMap = resourceMap;
    }

    /**
     * A private inner class to be used with the MCPPatches only.
     */
    private class ContextProvider implements ContextualPatch.IContextProvider {
        private Map<String, String> fileMap;

        private final int STRIP = 1;

        public ContextProvider(Map<String, String> fileMap) {
            this.fileMap = fileMap;
        }

        private String strip(String target) {
            target = target.replace('\\', '/');
            int index = 0;
            for (int x = 0; x < STRIP; x++) {
                index = target.indexOf('/', index) + 1;
            }
            return target.substring(index);
        }

        @Override
        public List<String> getData(String target) {
            target = strip(target);

            if (fileMap.containsKey(target)) {
                String[] lines = fileMap.get(target).split("\r\n|\r|\n");
                return new ArrayList<>(Arrays.asList(lines));
            }

            return null;
        }

        @Override
        public void setData(String target, List<String> data) {
            fileMap.put(strip(target), String.join(Constants.NEWLINE, data));
        }
    }
}
