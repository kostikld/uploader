# SFTP Uploader

SFTP Uploader is an IntelliJ IDEA plugin for uploading project files to remote servers over SFTP or `rsync`. It supports reusable server profiles, project-to-server path mappings - a row can select files by directory or by an fnmatch pattern - remote file comparison, and running saved commands on a server.

## Requirements

- IntelliJ IDEA 2023.3 or later, with Java support enabled (the plugin resolves Java code, so it does not
   load in an IDE without it)
- Network access to the target server over SSH/SFTP
- A username and password accepted by that server, configured once as credentials and picked by the servers
   that use it
- `rsync` and `ssh` on your local machine when using rsync uploads

Credentials are configured once and shared by servers. A password is stored in the IntelliJ Password Safe
under the credentials entry it belongs to, never in the project configuration.

> [!WARNING]
> The plugin currently accepts server host keys automatically. Use it only with servers and networks you trust.

## Configure a server

1. Open the **SFTP Uploader** tool window.
2. Select **Add**.
3. Enter a profile name, host and port, then pick an entry from the **Credentials** dropdown - or pick
   **<new>** and type a username and password, which creates an entry for this server. A picked entry shows
   its username and password read-only: they belong to the entry, not to the server.
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

### Share credentials between servers

A username several servers use is configured once instead of typed into every profile. The tool window tree
has two folders: **Servers** and **Credentials**. One row in **Credentials** is one username that any number
of servers can pick, and the tree shows it as `name — username`.

- Right-click the **Credentials** folder for **Add Credentials**, **Copy Credentials** and
   **Import Credentials**; right-click an entry for **Edit Credentials** and **Remove Credentials**.
   `Delete` removes the selected entry.
- The entry dialog takes a name, a username and a password, and says whether a password is already stored.
   Leaving the password blank keeps the stored one.
- In the server dialog, a picked entry fills the username and shows the password as `********`, both
   read-only. Choose **<new>** in that dropdown when the entry does not exist yet; the entry you chose last is
   already selected for a new server.
- With **<new>** chosen, the **Safe for others** box decides what happens to the typed username. On (the
   default, and it remembers your last choice) the username becomes an entry in the **Credentials** folder that
   other servers can pick. Off keeps the credentials with this server alone: no entry is created and the
   password is stored under the server itself.
- Editing an entry's username keeps the entry: every server picking it follows the new username.
- Removing an entry that servers still pick asks first, naming them. Confirming deletes the entry and its
   password and leaves those servers without credentials - they are listed with their old username until you
   give them new ones in the server dialog.
- **Copy Credentials** puts the list on the clipboard as `name -> username`, and **Import Credentials** reads
   that format back. A password never leaves the Password Safe, so an imported entry needs one set with
   **Edit Credentials**.

Servers configured before credentials entries existed keep working: they were given an entry named after their
username, and their stored password is read from where it was saved until it is typed in again.

### Share mappings and actions with a facility

A **facility** is a server type - `Payment Service`, `Customer Service` - with the path mappings and actions
every server of that type shares, so those are configured once instead of per server. The tool window tree
has a **Facilities** folder next to **Servers** and **Credentials**: right-click it for **Add Facility**,
right-click an entry for **Edit Facility** and **Remove Facility** (`Delete` removes the selected one), then
pick the facility in the **Facility (server type)** dropdown of the server dialog.

- A server inherits the facility's mappings and actions everywhere it is used: upload, compare, copy to
  local, git changes, and the **Actions** submenu.
- The server's own rows win: a mapping with the same local path replaces the facility's one, an action with
  the same name replaces the facility's one. Rows that override are highlighted in the server dialog, and
  what the facility supplies is listed under the collapsed **Inherited from facility** section.
- Editing a facility reaches every server picking it at once. Removing one asks first, naming the servers;
  those servers keep their own mappings and actions.
- Cloning a server keeps its facility.

### Fnmatch patterns

Files that are scattered over the project are often easier to select with a pattern than with a
directory, so the same table also takes patterns:

| Local path or pattern | Remote directory |
| --- | --- |
| `src/web/**` | `/var/www/static` |

A row is read as a pattern when its local side contains `*`, `?`, or `[`. Patterns are matched against
the project-relative path of a file: `*` matches any characters (including `/`), `?` matches one
character, and `[a-z]` (or `[!a-z]` for the opposite) matches a character class. Matching is
case-sensitive.

What the plugin keeps of the local path decides the folders it creates on the server:

- A pattern whose last part is `**` keeps only what that `**` matched, so the remote path starts below
  the last directory written in the pattern. With `src/web/**` above, `src/web/js/app.js` is uploaded
  to `/var/www/static/js/app.js`. A wildcard earlier in the pattern does not move that start:
  `module/*/target/classes/**` with remote `/app/APP-INF/classes` uploads
  `module/sub-module/target/classes/com/X.class` to `/app/APP-INF/classes/com/X.class`.
- Any other pattern keeps everything below its wildcard-free prefix: with `target/classes/**/ui/*.js`,
  `target/classes/com/example/ui/View.js` keeps `com/example/ui/View.js`.
- A direct path keeps everything below the local directory, the same way.

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

### Analyze constants changes

Java copies the value of a `static final` constant into the bytecode of every class that reads it, so changing
such a value changes those classes even when their own files did not change. The **Upload changed files** dialog
has an **Analyze Constants Changes** button for that: it compares every listed Java file with its earlier version,
finds the constants whose value differs, and adds the classes that read them.

The list is then split into two sections:

- **Changed in Git**: the files reported by Git.
- **Classes reading a changed constant**: the Java files that read a constant whose value differs, together with
   their compiled classes.

A class that appears in both sections is shown twice and checked or unchecked once, and it is uploaded once.
Hovering over a file in the second section shows the constant that put it there, for example
`Reads a changed constant: MAX: 10 -> 20`.

After the analysis the dialog opens again with everything you chose kept - the server, the checked files and the
zip choice - so you can press the button once more or confirm and upload. Pressing it never uploads anything by
itself, and the constant's own file does not have to be checked or even uploadable: it is enough that it is in
the list, since the classes reading it are often in another module with their own mapping.

The button is hidden when **Upload compiled classes for Java files** is off, and when none of the listed files
comes from a Java file, for example in a Kotlin only project: in both cases there is no constant that could
affect another class. Only compile-time constants are considered - `static final` fields of a primitive
or `String` type with a constant expression, in a class or in a nested interface; a field initialized by a method
call keeps its value at runtime and therefore needs no other class.

The classes reading a changed constant are found by the IDE's Java code index, which resolves qualified names,
static imports and inherited names exactly. When that index cannot answer - a folder opened without a project
import, indexing still running - the plugin falls back to reading the Java sources under the project and looking
for the constant's name outside comments; that is a wider net, so the listed files are worth a look before
uploading. Kotlin sources are not searched either way, and a search that finds nothing says so in one line.

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
A server also lists the actions of its [facility](#share-mappings-and-actions-with-a-facility); its own action with the same name replaces the inherited one.

Only save and run commands you trust: actions execute on the remote server as the configured user.

## Keyboard editing of entries

### Clone with the editor's duplicate key

Use the key the editor uses for "Duplicate Line or Selection": `Cmd+D` on macOS, `Ctrl+D` on Windows and
Linux. It clones whatever is selected:

- **Server**: select it in the tool window and press the key. The copy keeps the host, port, credentials,
   rsync setting, folder, path mappings and actions, and gets a new id: both servers authenticate through the
   same credentials entry, so no password is copied. Its name continues the sequence: `serve` -> `serve(1)` ->
   `serve(2)`.
- **Path mapping**: in the server dialog, copies of the selected rows are inserted below the selection.
   Local paths and patterns must stay unique, so edit a copy before saving.
- **Action**: the copy is inserted below with a new id and a sequenced name, since action names must be
   unique: `Deploy` -> `Deploy(1)`.

The **Local** server entry, folders and credentials entries cannot be cloned - edit or add them instead.

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
