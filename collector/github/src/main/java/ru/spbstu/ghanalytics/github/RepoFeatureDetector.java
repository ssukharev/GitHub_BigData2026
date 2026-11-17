package ru.spbstu.ghanalytics.github;

import ru.spbstu.ghanalytics.github.dto.TreeEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code has_tests}, {@code has_ci} and {@code has_docker} from the names in the root, {@code src}
 * and {@code .github} directories of HEAD (the trees {@code rootTree}, {@code srcTree} and
 * {@code dotGithub} of {@code RepoFields}). Names are compared in lower case; a directory
 * ({@code tree}) and a file ({@code blob}) with the same name are different signals.
 * <table>
 *   <caption>Signals</caption>
 *   <tr><th>Feature</th><th>Signal</th></tr>
 *   <tr><td>has_tests</td><td>a root directory {@code test}, {@code tests}, {@code __tests__}, {@code spec},
 *       {@code specs}; a directory {@code test}, {@code tests}, {@code __tests__} in {@code src};
 *       a root file {@code test_*.py}, {@code *_test.py}, {@code *_test.go}, {@code *_spec.rb},
 *       {@code *.test.js}, {@code *.spec.ts} and the other JS/TS extensions</td></tr>
 *   <tr><td>has_ci</td><td>a directory {@code workflows} in {@code .github}; in the root a file
 *       {@code .gitlab-ci.yml}, {@code .travis.yml}, {@code Jenkinsfile}, {@code azure-pipelines.yml},
 *       {@code bitbucket-pipelines.yml} or a directory {@code .circleci}</td></tr>
 *   <tr><td>has_docker</td><td>a root file {@code Dockerfile}, {@code Dockerfile.*},
 *       {@code docker-compose.yml}/{@code .yaml}, {@code compose.yml}/{@code .yaml}</td></tr>
 * </table>
 * The limits of these heuristics (monorepos, tests next to the code, CI outside the repository)
 * are listed in {@code docs/data/heuristics-notes.md}. Stateless and thread-safe.
 */
public class RepoFeatureDetector {

    private static final String TREE = "tree";
    private static final String BLOB = "blob";

    private static final Set<String> ROOT_TEST_DIRS = Set.of("test", "tests", "__tests__", "spec", "specs");
    private static final Set<String> SRC_TEST_DIRS = Set.of("test", "tests", "__tests__");
    private static final Pattern TEST_FILE = Pattern.compile(
            "test_.+\\.py|.+_test\\.py|.+_test\\.go|.+_spec\\.rb|.+\\.(test|spec)\\.[cm]?[jt]sx?");

    private static final Set<String> CI_FILES = Set.of(
            ".gitlab-ci.yml", ".travis.yml", "jenkinsfile", "azure-pipelines.yml", "bitbucket-pipelines.yml");
    private static final Set<String> CI_DIRS = Set.of(".circleci");

    private static final Set<String> DOCKER_FILES = Set.of(
            "dockerfile", "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml");

    /**
     * @param root      entries of the root; null for an empty repository: every feature is unknown
     * @param src       entries of {@code src}; null if there is no such directory
     * @param dotGithub entries of {@code .github}; null if there is no such directory
     */
    public RepoFeatures detect(List<TreeEntry> root, List<TreeEntry> src, List<TreeEntry> dotGithub) {
        if (root == null) {
            return RepoFeatures.UNKNOWN;
        }
        Names r = Names.of(root);
        Names s = Names.of(src);
        Names g = Names.of(dotGithub);
        return new RepoFeatures(hasTests(r, s), hasCi(r, g), hasDocker(r));
    }

    private static boolean hasTests(Names root, Names src) {
        return root.dirs().stream().anyMatch(ROOT_TEST_DIRS::contains)
                || src.dirs().stream().anyMatch(SRC_TEST_DIRS::contains)
                || root.files().stream().anyMatch(name -> TEST_FILE.matcher(name).matches());
    }

    /** Git keeps no empty directories, so a {@code workflows} directory holds at least one file. */
    private static boolean hasCi(Names root, Names dotGithub) {
        return dotGithub.dirs().contains("workflows")
                || root.files().stream().anyMatch(CI_FILES::contains)
                || root.dirs().stream().anyMatch(CI_DIRS::contains);
    }

    private static boolean hasDocker(Names root) {
        return root.files().stream().anyMatch(name -> DOCKER_FILES.contains(name) || name.startsWith("dockerfile."));
    }

    /** Lower-case names of directories and of files; submodules ({@code commit}) are neither. */
    private record Names(Set<String> dirs, Set<String> files) {

        static Names of(List<TreeEntry> entries) {
            Set<String> dirs = new HashSet<>();
            Set<String> files = new HashSet<>();
            if (entries != null) {
                for (TreeEntry entry : entries) {
                    if (entry == null || entry.name() == null || entry.type() == null) {
                        continue;
                    }
                    String name = entry.name().toLowerCase(Locale.ROOT);
                    if (TREE.equals(entry.type())) {
                        dirs.add(name);
                    } else if (BLOB.equals(entry.type())) {
                        files.add(name);
                    }
                }
            }
            return new Names(dirs, files);
        }
    }
}
