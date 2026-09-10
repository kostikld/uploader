<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Uploader Changelog

## Unreleased

### Added

- Copy all path mappings or actions to the clipboard as shareable text lines and import them back
   (format: `local path -> remote path` and `name -> command`).
- "Upload changed files" button in the tool window: scan the Git working tree for
   added/modified/renamed files, preselect each in a checkbox list (unmapped or uncompiled
   rows disabled), and upload the selected set to a chosen server. Java files are uploaded as
   their compiled classes when available.

## 1.0.2-SNAPSHOT - 2026-09-09

### Added

- Shared SFTP server profiles with passwords stored in IntelliJ Password Safe.
- Hierarchical project-relative to remote path mappings.
- Project View context-menu uploads with background progress and notifications.

## 1.0.3-SNAPSHOT - 2026-10-09
- If project is a git directory, then there is ability to list all changed files identified by GIT and upload selected
