package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.spbstu.ghanalytics.github.dto.TreeEntry;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trees are written as space-separated names: {@code tests/} is a directory ({@code tree}),
 * {@code tests} is a file ({@code blob}); an empty column is a missing tree (null).
 */
class RepoFeatureDetectorTest {

    private final RepoFeatureDetector detector = new RepoFeatureDetector();

    @ParameterizedTest(name = "root [{0}], src [{1}] -> tests={2}")
    @CsvSource(delimiter = '|', value = {
            "src/ pom.xml               | main/ test/   | true",    // Maven and Gradle: src/test
            "src/ package.json          | __tests__/    | true",
            "src/ setup.py              | tests/        | true",
            "tests/ README.md           |               | true",
            "Tests/ README.md           |               | true",    // case does not matter
            "spec/ Gemfile              |               | true",
            "specs/                     |               | true",
            "__tests__/ package.json    |               | true",
            "main.go main_test.go       |               | true",
            "test_app.py app.py         |               | true",
            "app_test.py                |               | true",
            "app.test.js                |               | true",
            "button.spec.ts             |               | true",
            "widget.test.tsx            |               | true",
            "user_spec.rb               |               | true",
            "tests README.md            |               | false",   // a file named tests, not a directory
            "src/ Cargo.toml            | main.rs lib.rs | false",
            "src/                       | test          | false",   // a file named test inside src
            "latest.go contest.py test.py testing.md |  | false",
            "docs/ README.md            |               | false"
    })
    void detectsTests(String root, String src, boolean expected) {
        assertThat(detector.detect(entries(root), entries(src), null).hasTests()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "root [{0}], .github [{1}] -> ci={2}")
    @CsvSource(delimiter = '|', value = {
            ".gitlab-ci.yml README.md   |                           | true",
            ".github/ README.md         | workflows/                | true",
            ".github/                   | ISSUE_TEMPLATE/ workflows/ dependabot.yml | true",
            ".travis.yml                |                           | true",
            "Jenkinsfile                |                           | true",
            ".circleci/                 |                           | true",
            "azure-pipelines.yml        |                           | true",
            "bitbucket-pipelines.yml    |                           | true",
            ".github/                   | ISSUE_TEMPLATE/ dependabot.yml | false",
            "README.md                  |                           | false",   // no .github at all
            ".github/                   | workflows                 | false",   // a file named workflows
            "workflows/ README.md       |                           | false",   // workflows outside .github
            ".circleci                  |                           | false"    // a file, not the CircleCI directory
    })
    void detectsCi(String root, String dotGithub, boolean expected) {
        assertThat(detector.detect(entries(root), null, entries(dotGithub)).hasCi()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "root [{0}] -> docker={1}")
    @CsvSource(delimiter = '|', value = {
            "Dockerfile               | true",
            "dockerfile               | true",
            "Dockerfile.dev           | true",
            "docker-compose.yml       | true",
            "docker-compose.yaml      | true",
            "compose.yml              | true",
            "compose.yaml             | true",
            ".dockerignore README.md  | false",
            "docker/ deploy/          | false",   // a Dockerfile deeper than the root is not seen
            "Dockerfile/              | false",   // a directory, not a file
            "README.md                | false"
    })
    void detectsDocker(String root, boolean expected) {
        assertThat(detector.detect(entries(root), null, null).hasDocker()).isEqualTo(expected);
    }

    @Test
    void onlyReadmeGivesAllFalse() {
        assertThat(detector.detect(entries("README.md"), null, null))
                .isEqualTo(new RepoFeatures(false, false, false));
    }

    @Test
    void emptyRepositoryIsUnknownNotFalse() {
        assertThat(detector.detect(null, entries("test/"), entries("workflows/"))).isEqualTo(RepoFeatures.UNKNOWN);
        assertThat(RepoFeatures.UNKNOWN).isEqualTo(new RepoFeatures(null, null, null));
    }

    @Test
    void allThreeAtOnce() {
        assertThat(detector.detect(entries(".github/ src/ Dockerfile pom.xml"), entries("main/ test/"), entries("workflows/")))
                .isEqualTo(new RepoFeatures(true, true, true));
    }

    @Test
    void entriesWithoutNameOrTypeAreIgnored() {
        List<TreeEntry> root = Arrays.asList(null, new TreeEntry(null, "tree"), new TreeEntry("tests", null));

        assertThat(detector.detect(root, null, null)).isEqualTo(new RepoFeatures(false, false, false));
    }

    /** {@code "tests/ README.md"}: a directory and a file; null stays null (no such tree). */
    private static List<TreeEntry> entries(String names) {
        if (names == null) {
            return null;
        }
        return Arrays.stream(names.trim().split("\\s+"))
                .filter(n -> !n.isEmpty())
                .map(n -> n.endsWith("/") ? new TreeEntry(n.substring(0, n.length() - 1), "tree") : new TreeEntry(n, "blob"))
                .toList();
    }
}
