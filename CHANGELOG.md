<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Uploader Changelog

## Unreleased

- A username is configured once as a **credentials** entry and picked by any number of servers, instead of typing
   a username and password into every server profile. The tool window tree now has two folders, **Servers** and
   **Credentials**; right-click the credentials folder or one of its entries to add, edit, remove, copy or import
   them, and the server dialog has a **Credentials** dropdown: choose a saved entry, or the first item to create a
   new one for this server.
- In the server dialog a picked entry shows its username and password - as `********` - read-only, because both
   belong to the entry. The entry dialog is where its password is typed or changed; leaving it blank keeps the
   stored one.
- Servers configured earlier keep working: each one whose username was inline was given an entry named after that
   username - servers sharing a username share one entry - and its stored password is still read from where it was
   saved until it is typed in again.
- Removing a server keeps its credentials entry for the next server to pick. Removing an entry that servers
   still pick asks for confirmation and names them: the entry and its password are deleted and those servers are
   left without credentials, rather than the deletion being blocked.
- The server dialog has a **Safe for others** box, shown only for the new-credentials choice. On (the default,
   and the dialog remembers the last choice) the typed username becomes an entry in the **Credentials** folder;
   off keeps the username and password with that one server.
- A new server starts with the credentials entry chosen last.
- Cloning a server keeps authenticating through the same entry, so no password is copied.
- Credentials entries are copied to the clipboard and imported as `name -> username`; the password never leaves
   the IntelliJ Password Safe.
- The **Servers** folder itself takes a right-click for **Add Folder**, since it is now a folder in the tree rather
   than its hidden root.

## 1.0.15 - 2026-11-06

- The dialog has an **Analyze Constants Changes** button: it compares the listed Java files with their earlier
   version, finds the constants whose value differs, and adds the classes that read them to the list below. It is hidden while
   **Upload compiled classes for Java files** is off or when none of the listed files comes from a Java file.
- A class that both changed in Git and reads a changed constant is listed in both sections, is checked or unchecked
   once, and is uploaded once.
- The plugin now needs the IDE's Java support to resolve the classes that read a constant, so it no longer loads
   in an IDE without it.

## 1.0.14 - 2026-11-05

- Right-click a folder for **Add Folder**, which creates a folder inside the one you clicked.
- A folder stays open when a server inside it is added, cloned or removed, and the selected entry
   stays selected after the list is rebuilt, so `Delete` and the duplicate key work right away.
- `Delete` (or `Backspace`) removes the selected path mapping or action rows in the server dialog,
   the same key that removes a selected server or folder in the tool window.
- The server selected in the tool window is preselected when "Upload GIT Changes" opens, instead of
   the first profile of the list.
- The messages for an existing folder and for a folder name containing `..` are shown as text
   instead of their internal keys.

## 1.0.13 - 2026-11-05

- Path mappings and fnmatch patterns are configured in one table: a row whose local side contains `*`,
   `?`, or `[` selects files by pattern, and the section label says patterns are supported. The rule
   - direct paths win over patterns, then the most concrete pattern wins - is shown above the table and
   in its tooltip. Profiles saved earlier have their separate fnmatch rows moved into this list, above
   the direct mappings, when the settings are loaded.

## 1.0.12 - 2026-11-03

- Path mappings for files selected by fnmatch pattern (`*`, `?`, `[a-z]`), configured in a section above the
   direct path mappings. Direct mappings win over patterns; among patterns the most concrete one wins.
- Tool window settings: hide the **Add Folder** button, and wrap the tool window buttons onto several
   rows when the window is too narrow for one row (a button moves down once more than half of it would be hidden).
- Clone a server, path mapping, fnmatch pattern or action with the editor's duplicate-line key
   (`Cmd+D` on macOS, `Ctrl+D` on Windows/Linux). A cloned server or action gets a sequenced name
   (`serve(1)`, `serve(2)`) and a new id, and the server's stored password is copied to the clone.

## 1.0.11 - 2026-10-20

- "Upload changed files" can list the files of a specific commit: enter a commit id, or leave it blank for current working-tree changes
- Fix server list background coloring in some themes

## 1.0.10 - 2026-10-08

- Config for a local directory

## 1.0.9 - 2026-10-07

- Organization servers in folders
- Template params for actions

## 1.0.8 - 2026-09-24

- Fix "Upload compiled to remote" using rsync when a class has inner classes

## 1.0.6-SNAPSHOT - 2026-09-18

- Support since 2023.3.3

## 1.0.5-SNAPSHOT - 2026-09-16

- Compare file with remote

## 1.0.4-SNAPSHOT - 2026-09-16

- Support Rsync, Disabled by default
- Small UI improvements

## 1.0.3-SNAPSHOT - 2026-09-10

- If a project is a git directory, then there is an ability to list all changed files identified by GIT and upload selected
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
