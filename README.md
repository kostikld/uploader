# SFTP Uploader

SFTP Uploader is an IntelliJ IDEA plugin for uploading project files to remote servers over SFTP or `rsync`. It supports reusable server profiles, project-to-server path mappings - a row can select files by directory or by an fnmatch pattern - remote file comparison, and running saved commands on a server.

## Requirements

- IntelliJ IDEA 2023.3 or later
- Network access to the target server over SSH/SFTP
- A username and password accepted by that server
- `rsync` and `ssh` on your local machine when using rsync uploads

Passwords are stored in the IntelliJ Password Safe, not in the project configuration.

> [!WARNING]
> The plugin currently accepts server host keys automatically. Use it only with servers and networks you trust.

## Configure a server

1. Open the **SFTP Uploader** tool window.
2. Select **Add**.
3. Enter a profile name, host, port, username, and password.
4. Add one or more path mappings:
   - **Local path or pattern**: the local directory, relative to the project root, or a pattern
     that selects files by name.
   - **Remote directory**: the corresponding absolute directory on the server.
5. Save the profile. Use **Test Connection** from the profile's context menu to verify it.

For example, this mapping uploads `src/main/resources/app.yml` to `/var/www/app/src/main/resources/app.yml`:

| Local path or pattern | Remote directory |
| --- | --- |
| `/` | `/var/www/app` |

When mappings overlap, the most specific local path is used.

### Fnmatch patterns

Files that are scattered over the project are often easier to select with a pattern than with a
directory, so the same table also takes patterns:

| Local path or pattern | Remote directory |
| --- | --- |
| `src/web/**` | `/var/www/static` |

A row is read as a pattern when its local side contains `*`, `?`, or `[`. Patterns are matched against
the project-relative path of a file: `*` matches any characters (including `/`), `?` matches one
character, and `[a-z]` (or `[!a-z]` for the opposite) matches a character class. Matching is
case-sensitive. Everything below the pattern's wildcard-free prefix is kept, the same way path mappings
keep the path below their local directory: with the mapping above, `src/web/js/app.js` is uploaded to
`/var/www/static/js/app.js`.

A direct path always wins over a pattern for the same file. When several patterns match a file, the
most concrete one is used: the longest wildcard-free prefix first, then the fewest wildcards, and
finally the order of the table.

A local directory whose name contains `*`, `?`, or `[` cannot be mapped: it is read as a pattern, and
this matcher has no escape character.

### Organize servers in folders

Set a profile's **Folder** to group it in the tool window; use `/` for nested folders, for example
`prod/eu`. Right-click a folder for:

- **Add Folder**: create a folder inside this one.
- **Rename Folder**: rename it, keeping nested folders and profiles inside with it.
- **Remove Folder**: delete it together with everything inside.

A folder you opened stays open when a profile inside it is added, cloned, or removed.

## Upload files

### From the Project tool window

Select one or more files, right-click, then choose one of these menus:

- **Upload to server**: uploads the selected files.
- **Upload compiled to server**: for Java files, uploads the corresponding compiled `.class` files.

Choose a configured server profile from the submenu. Required remote directories are created automatically.

### Upload changed files

In the **SFTP Uploader** tool window, select **Upload changed files**. The plugin prompts for a commit id: enter one to list the files changed in that commit, or leave it blank to use the current Git working tree. It then lets you choose a server and files, and uploads the selected items. The server selected in the tool window is preselected; pick another one from the list if needed.

Files that do not match a mapping are disabled. When compiled Java uploads are enabled, Java files without a compiled class are also disabled.

## Compare with the server

Select files in the **Project** tool window, right-click, and use:

- **Compare with server** to compare local source files with their remote versions.
- **Compare compiled with server** to compare compiled Java classes with their remote versions.

The plugin opens IntelliJ's diff viewer for files found on the server and reports remote files that are missing.

## Upload options

Open **Settings | Tools | SFTP Uploader**:

- **Upload Java class with inner classes**: include a Java class's inner-class files.
- **Upload compiled classes for Java files**: make normal uploads use compiled Java classes.
- **New servers use rsync**: enable rsync by default when creating a profile.

The settings dialog also has a **Tool window buttons** section for the buttons at the bottom of the tool window:

- **Show "Add Folder" button**: add the **Add Folder** button. Off by default.
- **Show buttons in several rows when they do not fit**: when the window is too narrow for one row, buttons
   that would be more than half hidden move to a new row. On by default.

Both apply the next time the tool window is refreshed.

Each server profile can also enable **Upload using rsync**. If an rsync upload fails, the plugin offers an SFTP fallback.

## Server actions

Add named commands to a server profile under **Actions**. Right-click that server in the tool window and select an action to execute it through SSH. The result, exit status, and up to 64 KiB of output are shown in an IDE notification.
You can use template parameters in actions. For example, `date -l ${option}` will prompt for an option for date.

Only save and run commands you trust: actions execute on the remote server as the configured user.

## Keyboard editing of entries

### Clone with the editor's duplicate key

Use the key the editor uses for "Duplicate Line or Selection": `Cmd+D` on macOS, `Ctrl+D` on Windows and
Linux. It clones whatever is selected:

- **Server**: select it in the tool window and press the key. The copy keeps the host, port, username,
   rsync setting, folder, path mappings and actions, gets a new id, and the stored
   password is copied to it. Its name continues the sequence: `serve` -> `serve(1)` -> `serve(2)`.
- **Path mapping**: in the server dialog, copies of the selected rows are inserted below the selection.
   Local paths and patterns must stay unique, so edit a copy before saving.
- **Action**: the copy is inserted below with a new id and a sequenced name, since action names must be
   unique: `Deploy` -> `Deploy(1)`.

The **Local** server entry and folders cannot be cloned - edit or rename them instead.

### Remove with Delete

`Delete` (or `Backspace`) removes what is selected, in both places:

- **Server or folder**: in the tool window. A folder asks for confirmation and removes the folders and
   servers inside it; the **Local** entry cannot be removed.
- **Path mapping or action**: in the server dialog, every selected row of the table is removed. While
   you are typing in a cell, the key belongs to the text field, so press it again after finishing the
   edit to remove the row.

Both tables also have the **Remove Mapping** and **Remove Action** buttons.

## Import and export

Path mappings and server actions can be copied to the clipboard and imported into another profile.

- Mapping format: `local path or pattern -> remote path`
- Action format: `name -> command`

## Build from source

```sh
./gradlew build
```

Run plugin tests with:

```sh
./gradlew test
```

The plugin is compiled against IntelliJ IDEA 2023.3.3 and declares compatibility from platform build `233`.

## Manual integration tests

Integration tests live in a dedicated source set under `src/integrationTest/`. They spin up
a real container and are **not** part of the default `test` suite; run them manually with a
local container engine (Docker or Podman):

```sh
./gradlew integrationTest
```

For example, `RsyncSshContainerTest` uploads a preconfigured directory tree to a real
SSH/rsync container over `rsync` and verifies the transferred files are identical. When the
image `alpine_rsync_ssh` is already present locally, it is used as-is; otherwise the image is
built from `docker/alpine_rsync_ssh/Dockerfile`. The image and fallback Dockerfile can be
overridden with `-PrsyncImage` and `-PrsyncDockerfile`. Under Podman, point Testcontainers
at the machine's engine socket, for example:

```sh
DOCKER_HOST=unix://$(podman machine inspect --format '{{json .ConnectionInfo.PodmanSocket.Path}}' | tr -d '"') \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew integrationTest
```
