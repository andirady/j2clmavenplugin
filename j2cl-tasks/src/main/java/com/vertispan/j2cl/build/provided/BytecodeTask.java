package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.Javac;

import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This runs javac (and so, all annotation processors) on the input source, and
 * produces a directory of all sources, resources, and bytecode of a reactor
 * project. This results in a directory with the same contents you might get from
 * unpacking the jar from a non-reactor dependency, so we can treat all
 * dependencies the same, regardless of their origin.
 */
@AutoService(TaskFactory.class)
public class BytecodeTask extends TaskFactory {

    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");
    public static final PathMatcher JAVA_BYTECODE = withSuffix(".class");
    public static final PathMatcher NOT_BYTECODE = p -> !JAVA_BYTECODE.matches(p);

    @Override
    public String getOutputType() {
        return OutputTypes.BYTECODE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        if (!project.hasSourcesMapped()) {
            // instead, copy the bytecode+resources out of the jar so it can be used by downstream bytecode/apt tasks
            Input existingUnpackedBytecode = input(project, OutputTypes.INPUT_SOURCES);
            return context -> {
                for (CachedPath entry : existingUnpackedBytecode.getFilesAndHashes()) {
                    Path outputFile = context.outputPath().resolve(entry.getSourcePath());
                    Files.createDirectories(outputFile.getParent());
                    Files.copy(entry.getAbsolutePath(), outputFile);
                }
            };
        }

        // TODO just use one input for both of these
        // track the dirs (with all file changes) so that APT can see things it wants
        Input inputDirs = input(project, OutputTypes.INPUT_SOURCES);
        // track just java files (so we can just compile them)
        Input inputSources = input(project, OutputTypes.INPUT_SOURCES).filter(JAVA_SOURCES);
        // track resources so they are available to downstream processors on the classpath, as they would
        // be if we had built a jar
        Input resources = input(project, OutputTypes.INPUT_SOURCES).filter(NOT_BYTECODE);

        List<Input> bytecodeClasspath = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.BYTECODE))
                .collect(Collectors.toList());

        File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = config.getExtraClasspath();
        return context -> {
            if (!inputSources.getFilesAndHashes().isEmpty()) {
                // At least one .java file in sources, compile it (otherwise skip this and just copy resource)

                List<File> classpathDirs = Stream.concat(
                        bytecodeClasspath.stream().map(Input::getParentPaths).flatMap(Collection::stream).map(Path::toFile),
                        extraClasspath.stream()
                ).collect(Collectors.toList());

                List<File> sourcePaths = inputDirs.getParentPaths().stream().map(Path::toFile).collect(Collectors.toList());
                File generatedClassesDir = getGeneratedClassesDir(context);
                File classOutputDir = context.outputPath().toFile();
                Javac javac = new Javac(context, generatedClassesDir, sourcePaths, classpathDirs, classOutputDir, bootstrapClasspath);

                // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
                //      automatically relativized?
                List<SourceUtils.FileInfo> sources = inputSources.getFilesAndHashes()
                        .stream()
                        .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                        .collect(Collectors.toList());

                try {
                    if (!javac.compile(sources)) {
                        throw new RuntimeException("Failed to complete bytecode task, check log");
                    }
                } catch (Exception exception) {
                    exception.printStackTrace();
                    throw exception;
                }
            }

            // Copy all resources, even .java files, so that this output is the source of truth as if this
            // were freshly unpacked from a jar
            for (CachedPath entry : resources.getFilesAndHashes()) {
                Files.createDirectories(context.outputPath().resolve(entry.getSourcePath()).getParent());
                Files.copy(entry.getAbsolutePath(), context.outputPath().resolve(entry.getSourcePath()));
            }

        };
    }

    @Nullable
    protected File getGeneratedClassesDir(TaskContext context) {
        return context.outputPath().toFile();
    }
}
