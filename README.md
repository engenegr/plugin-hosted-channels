### Compiling Hosted Channels plugin

```
$ cd <plugin-hosted-channels>
$ sbt
sbt$ assembly
```

JAR file can be found in `target` folder.

### Compiling AlarmBot plugin

Hosted Channels plugin uses [AlarmBot](https://github.com/engenegr/eclair-alarmbot-plugin) plugin to send out 
custom Telegram messages, and Eclair instance should be run with both of these plugins added to work correctly.

```
$ cd <eclair-alarmbot-plugin>
$ mvn clean install
```

Again, JAR file can be found in `target` folder.

### Running

1. Install Postgresql.
2. Create a new db called `hc` for example, same db name should be provided in HC config file.
3. Create a folder `<eclair datadir>/plugin-resources/hosted-channels/`.
4. Copy `hc.conf` file found in this repository into that folder and edit it accordingly.
5. Run `eclair-node-<version>-<commit_id>/bin/eclair-node.sh 'hc-assembly-0.2.jar' 'eclair-alarmbot.jar'`.