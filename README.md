### Compiling Hosted Channels plugin

```
$ cd <plugin-hosted-channels>
$ sbt
sbt$ assembly
```

JAR file can be found in `target` folder.

### Compiling AlarmBot plugin

HC plugin depends on [AlarmBot](https://github.com/engenegr/eclair-alarmbot-plugin) plugin to send out 
custom Telegram messages, and Eclair instance must be run with both of these plugins added, 
compile AlarmBot as follows:

```
$ cd <eclair-alarmbot-plugin>
$ mvn clean install
```

Again, JAR file can be found in `target` folder.  

AlarmBot plugin is especially important when you have Client hosted channels and Host is stalling a resolution of your incoming
payment. This is an adversarial situation where you need to take certain actions within a certain timeframe to timestamp Host's
obligations regarding a stalling payment, and it's important to be notified about this in time.

### Running

1. Install Postgresql.
2. Create a new db called `hc` for example, same db name should be provided in HC config file.
3. Create a folder `<eclair datadir>/plugin-resources/hosted-channels/`.
4. Copy `hc.conf` file found in this repository into that folder and edit it accordingly.
5. Setup AlarmBot as specified in its readme.
6. Run `eclair-node-<version>-<commit_id>/bin/eclair-node.sh 'hc-assembly-0.2.jar' 'eclair-alarmbot.jar'`.

---

## API Reference

HC plugin extends base Eclair API with HC-specific methods.  
HC API is accessed in a same way as base API e.g.  `./eclair-cli.sh hc-phcnodes`.

method                                                                                                    | description                                                                         
----------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------
`hc-findbyremoteid --nodeId=<remote nodeId>`                                                              | Find and display HC Json details with remote `nodeId`, if such a channel exists.
`hc-invoke --refundAddress=<Bitcoin address> --secret=<0-64 bytes of data in hex> --nodeId=<Host nodeId>` | Invokes a new HC with remote `nodeId`, if accepted your node will be a Client side. Established HC is private by default.
`hc-makepublic --nodeId=<remote nodeId>`                                                                  | Propose to make an HC with remote `nodeId` a public one. Both peers need to call this method for HC to become public.
`hc-makeprivate --nodeId=<remote nodeId>`                                                                 | Make public HC with remote `nodeId` a private one. Please note that other nodes will still keep it in their PHC graphs for about 14 days.
`hc-resize --newCapacitySat=<new capacity> --nodeId=<remote nodeId>`                                      | Increase an existing HC capacity if HC is resizeable. This command should only be issued from Client side, resize attempt from Host side will result an HC suspending immediately.