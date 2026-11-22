package ru.spbstu.ghanalytics.github.dto;

/**
 * One entry of a git tree from {@code object(expression: "HEAD:...") { ... on Tree { entries { name type } } }}.
 *
 * @param name file or directory name without a path
 * @param type {@code blob} (file, also a symlink), {@code tree} (directory) or {@code commit} (submodule)
 */
public record TreeEntry(String name, String type) {
}
