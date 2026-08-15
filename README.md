
To set up rclone for the sync.bat script:
1. Install rclone from https://rclone.org/
2. Run ```rclone config``` in your terminal
3. Make a new entry called "testserver"
4. Select sftp (55)
5. Enter host, user, and port
6. Type in the password for your sftp server
7. Press ctrl+c to quit editing the config
8. Run ```rclone config file```
9. Edit the file at that location and add these lines to the bottom of the [testserver] entry
```
    known_hosts_file = none
    key_use_agent = false
    disable_hashcheck = true
    shell_type = none
```


To change a script:

1. Run the sync shell script to sync mods to your computer and copy scripts from and to the server.
2. Sync the Gradle project if libs/ was updated
3. Put scripts in src/main/kotlin/ or a subdirectory of that.
4. Run the sync shell script again and then reload on the server with /jetscript reload

- Make sure the permissions for every subfolder is 755 after uploading as the server cannot read from the default folder permission

- To delete a script, do so on the server and delete local copies as the rclone will recreate any file that does not exist or is older on the server