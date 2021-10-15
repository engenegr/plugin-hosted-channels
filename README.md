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

method                                                                                                            | description                                                                         
------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------
**hc-findbyremoteid** `--nodeId=<remote nodeId>`                                                                  | Find and display HC Json details with remote `nodeId`, if such a channel exists.
**hc-invoke** `--nodeId=<Host nodeId> --refundAddress=<Bitcoin address> --secret=<0-64 bytes of data in hex>`     | Invokes a new HC with remote `nodeId`, if accepted your node will be a Client side. Established HC is private by default.
**hc-makepublic** `--nodeId=<remote nodeId>`                                                                      | Propose to make an HC with remote `nodeId` a public one. Both peers need to call this method for HC to become public.
**hc-makeprivate** `--nodeId=<remote nodeId>`                                                                     | Make public HC with remote `nodeId` a private one. Please note that other nodes will still keep it in their PHC graphs for about 14 days.
**hc-resize** `--nodeId=<remote nodeId> --newCapacitySat=<new capacity in SAT>`                                   | Increase an existing HC capacity if HC is resizeable. This command should only be issued from Client side, resize attempt from Host side will result an HC suspending immediately.
**hc-suspend** `--nodeId=<remote nodeId>`                                                                         | Manually suspend an HC with remote `nodeId`, in-flight payments present in HC at the moment of suspending will eventually become failed or fulfilled normally, but new payments won't be accepted.
**hc-overridepropose** `--nodeId=<remote nodeId> --newLocalBalanceMsat=<new local balance in MSAT>`               | Propose to reset an HC to a new state after it got suspended, can only be issued by Host. This removes all pending payments in HC and properly resolves them in upstream channels.
**hc-overrideaccept** `--nodeId=<remote nodeId>`                                                                  | Accept a Host-proposed HC override on client side. This will produce a new cross-signed HC state without pending payments and with Host-proposed balance distribution.
**hc-externalfulfill** `--nodeId=<remote nodeId> --htlcId=<outgoing HTLC id> --paymentPreimage=<32 bytes in hex>` | Manually fulfill an outgoing payment pending in HC, preimage and HTLC id are expected to be provided by other side of the channel out of band. Channel itself will get suspended after successful manual fulfill.
**hc-verifyremotestate** `--state=<remote state snapshot binary data in hex>`                                     | Verify that remote HC state snapshot is valid, meaning it is correctly cross-signed by local and remote `nodeId`s.
**hc-restorefromremotestate** `--state=<remote state snapshot binary data in hex>`                                | Restore a locally missing HC from remote HC state provided by peer given that it is correctly cross-signed by local and remote `nodeId`s.
**hc-broadcastpreimages** `--preimages=[<32 bytes in hex>, ...] --feerateSatByte=<feerate per byte>`              | `OP_RETURN`-timestamp preimages for pending incoming payments for which we have revealed preimages and yet the other side of channel have not cross-signed a clearing state update within reasonable time.
**hc-phcnodes**                                                                                                   | List `nodeIds` which are known to this node to support public HCs.
**hc-hot**                                                                                                        | List all HCs which contain pending payments.

## Handling of suspended HCs

HC gets suspended for same reasons normal channels get force-closed: for example due to receiving an invalid state update signature or an out-of-sync update, or because of expired outgoing HTLC. Once suspended, a Host may issue an `hc-overridepropose` command with a new balance distribution. If Client accepts it by issuing `hc-overrideaccept` command then new HC state gets cross-signed and channel becomes operational again.

A tricky point here is that HC may get suspended while having incoming and outgoing active HTLCs, and this situation requires careful handling from Host and Client to properly calculate a new balance in `hc-overridepropose`. Specifically, Host must wait until all active HTLCs get resolved in one way or another, and only after that it's safe to propose an override.

For OUT `... -> Host -> Client -> ...` payments there are 3 ways in which these HTLCs may get resolved:

1. Either Client provides a preimage for related HTLC off-chain before HTLC's CLTV expiry: in this case Host node resolves a payment upstream using a preimage immediately, and resolved payment info will be seen in logs later when Host inspects a suspended HC. An `UpdateFulfillHtlc` message will also be present in `nextRemoteUpdates` list of suspended HC.
2. Or Client provides a preimage on-chain via `hc-broadcastpreimages` command before HTLC's CLTV expiry: given that Host listens to Blockchain live data a suspended HC will be notified with chain-extracted preimage once it gets included in a block, and further progress will be similar to the first case.
3. Or client keeps doing nothing: in this case Host node waits until HTLC's CLTV expires and then fails a payment upstream.

In general this means that before issuing `hc-overridepropose` Host must make sure that all `Host -> Client` HTLCs are either fulfilled by Client in one way or another, or chain height got past all CLTV expiries. All fulfilled `Host -> Client` HTLCs must be subtracted from new Host HC balance, all failed ones must be added to new Host HC balance.

For IN `... <- Host <- Client <- ...` payments Host must wait until they either get failed or fulfilled downstream. All fulfilled `Host <- Client` HTLCs must be added to new Host HC balance, all failed ones must be added to new Client HC balance.
