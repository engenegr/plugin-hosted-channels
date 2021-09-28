### Compiling Hosted Channels plugin

```
$ cd <plugin-hosted-channels>
$ sbt
sbt$ assembly
```

JAR file can be found in `target` folder.

### Compiling AlarmBot plugin

HC plugin depends on [AlarmBot](https://github.com/engenegr/eclair-alarmbot-plugin) plugin to send out 
custom Telegram messages (more on this later), and Eclair instance must be run with both of these plugins added.

```
$ cd <eclair-alarmbot-plugin>
$ mvn clean install
```

Again, JAR file can be found in `target` folder.

### Running

1. Install Postgresql.
2. Create a new db called `hc` for example, same db name should be provided in HC config file.
3. Create a folder `<eclair datadir>/plugin-resources/hosted-channels/`.
4. Create a folder `<eclair datadir>/plugin-resources/hosted-channels/`.
5. Copy `hc.conf` file found in this repository into that folder and edit it accordingly.
6. Run `eclair-node-<version>-<commit_id>/bin/eclair-node.sh 'hc-assembly-0.2.jar' 'eclair-alarmbot.jar'`.

---
AlarmBot plugin is especially important when you have Client hosted channels and Host is stalling a resolution of your incoming
payment. This is an adversarial situation where you need to take certain actions within a timeframe to timestamp Host's
obligations regarding a stalling payment, and it's important to be notified about this in time.