package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vertispan.j2cl.build.task.*;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.ClosureTask.COPIED_OUTPUT;
import static com.vertispan.j2cl.build.provided.ClosureTask.copiedOutputPath;
import static com.vertispan.j2cl.build.provided.JsZipBundleTask.JSZIP_BUNDLE_OUTPUT_TYPE;

@AutoService(TaskFactory.class)
public class BundleJarTask extends TaskFactory {

    public static final PathMatcher BUNDLE_JS = FileSystems.getDefault().getPathMatcher("glob:*.bundle.js");

    @Override
    public String getOutputType() {
        return OutputTypes.BUNDLED_JS_APP;
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
        List<Input> jsSources = Stream
                .concat(
                        scope(project.getDependencies(), Dependency.Scope.RUNTIME)
                                .stream()
                                .map(inputs(OutputTypes.BUNDLED_JS)),
                        Stream.of(input(project, OutputTypes.BUNDLED_JS))
                )
                .map(i -> i.filter(BUNDLE_JS))
                .collect(Collectors.toList());

        // Sort the projects, to try to include them in order. We can't be sure that all project
        // dependencies will be (or should be) present, but we can make sure that we only load
        // our own JS after any dependencies that will be included have already loaded.
        List<Input> sourceOrder = new ArrayList<>();
        Set<String> pendingProjectKeys = jsSources.stream().map(i -> i.getProject().getKey()).collect(Collectors.toSet());
        List<Input> remaining = jsSources.stream().sorted(Comparator.comparing(i -> i.getProject().getDependencies().size())).collect(Collectors.toList());
        while (!remaining.isEmpty()) {
            for (Iterator<Input> iterator = remaining.iterator(); iterator.hasNext(); ) {
                Input input = iterator.next();
                if (input.getProject().getDependencies().stream().noneMatch(dep -> pendingProjectKeys.contains(dep.getProject().getKey()))) {
                    iterator.remove();
                    pendingProjectKeys.remove(input.getProject().getKey());
                    sourceOrder.add(input);
                }
            }
        }

        //cheaty, but lets us cache
        Input jszip = input(project, JSZIP_BUNDLE_OUTPUT_TYPE);

        File initialScriptFile = config.getWebappDirectory().resolve(config.getInitialScriptFilename()).toFile();
        Map<String, Object> defines = new LinkedHashMap<>(config.getDefines());

        List<Input> outputToCopy = Stream.concat(
                        Stream.of(project),
                        scope(project.getDependencies(), Dependency.Scope.RUNTIME).stream()
                )
                // Only need to consider the original inputs and generated sources,
                // J2CL won't contribute this kind of sources
                .map(p -> input(p, OutputTypes.BYTECODE).filter(COPIED_OUTPUT))
                .collect(Collectors.toList());

        return new FinalOutputTask() {
            @Override
            public void execute(TaskContext context) throws Exception {
                // We won't do any actual work in here, the jsSources and jszip work is doing everything we care about, and
                // since we already have to copy to output, we won't do it a second time.
                // Note that if we have BundleJarTask and ClosureTask both feed to an "actual final task" this assumption
                // will not be true anymore
            }

            @Override
            public void finish(TaskContext taskContext) throws IOException {
                // we technically still have access to all of the inputs, since we know that the
                // cachable task has already finished, and until we return it isn't possible for
                // a new compile to start

                for (Path dir : Stream.concat(Stream.of(jszip), jsSources.stream()).map(Input::getParentPaths).flatMap(Collection::stream).collect(Collectors.toSet())) {
                    FileUtils.copyDirectory(dir.toFile(), initialScriptFile.getParentFile());
                }

                try {
                    Gson gson = new GsonBuilder().setPrettyPrinting().create();
                    String scriptsArray = gson.toJson(Stream.concat(
                            Stream.of("j2cl-base.js"),//jre and bootstrap wiring, from the jszip input, always named the same
                            sourceOrder.stream().flatMap(i -> i.getFilesAndHashes().stream()).map(CachedPath::getSourcePath).map(Path::toString)
                    ).collect(Collectors.toList()));
                    // unconditionally set this to false, so that our dependency order works, since we're always in BUNDLE now
                    defines.put("goog.ENABLE_DEBUG_LOADER", false);

                    // defines are global, outside the IIFE
                    String defineLine = "var CLOSURE_UNCOMPILED_DEFINES = " + gson.toJson(defines) + ";\n";
                    // IIFE and base url
                    String intro = "(function() {" + "var src = document.currentScript.src;\n" +
                            "var lastSlash = src.lastIndexOf('/');\n" +
                            "var base = lastSlash === -1 ? '' : src.substr(0, lastSlash + 1);";

                    // iterate the scripts and append, close IIFE
                    String outro = ".forEach(file => {\n" +
                            "  var elt = document.createElement('script');\n" +
                            "  elt.src = base + file;\n" +
                            "  elt.type = 'text/javascript';\n" +
                            "  elt.async = false;\n" +
                            "  document.head.appendChild(elt);\n" +
                            "});" + "})();";
                    Files.write(initialScriptFile.toPath(), Arrays.asList(
                            defineLine,
                            intro,
                            scriptsArray,
                            outro
                    ));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write html import file", e);
                }
                for (Input input : outputToCopy) {
                    for (CachedPath entry : input.getFilesAndHashes()) {
                        copiedOutputPath(initialScriptFile.getParentFile().toPath(), entry);
                    }
                }
            }
        };
    }
}
