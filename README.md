# SFTP Uploader

SFTP Uploader is an IntelliJ IDEA plugin for uploading project files to remote servers over SFTP or `rsync`. It supports reusable server profiles, project-to-server path mappings, remote file comparison, and running saved commands on a server.

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
   - **Project-relative path**: the local directory, relative to the project root.
   - **Remote directory**: the corresponding absolute directory on the server.
5. Save the profile. Use **Test Connection** from the profile's context menu to verify it.

For example, this mapping uploads `src/main/resources/app.yml` to `/var/www/app/src/main/resources/app.yml`:

| Project-relative path | Remote directory |
| --- | --- |
| `/` | `/var/www/app` |

When mappings overlap, the most specific local path is used.

## Upload files

### From the Project tool window

Select one or more files, right-click, then choose one of these menus:

- **Upload to server**: uploads the selected files.
- **Upload compiled to server**: for Java files, uploads the corresponding compiled `.class` files.

Choose a configured server profile from the submenu. Required remote directories are created automatically.

### Upload changed files

In the **SFTP Uploader** tool window, select **Upload changed files**. The plugin finds changed files in the current Git working tree, lets you choose a server and files, then uploads the selected items.

Files that do not match a path mapping are disabled. When compiled Java uploads are enabled, Java files without a compiled class are also disabled.

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

Each server profile can also enable **Upload using rsync**. If an rsync upload fails, the plugin offers an SFTP fallback.

## Server actions

Add named commands to a server profile under **Actions**. Right-click that server in the tool window and select an action to execute it through SSH. The result, exit status, and up to 64 KiB of output are shown in an IDE notification.

Only save and run commands you trust: actions execute on the remote server as the configured user.

## Import and export

Path mappings and server actions can be copied to the clipboard and imported into another profile.

- Mapping format: `local path -> remote path`
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
