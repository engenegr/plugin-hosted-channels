## Compiling

### 1. Compiling Eclair itself

- Obtain compatible Eclair release source code and compile it:

```
$ cd <eclair>
$ git checkout v0.6.2
$ mvn clean install -DskipTests=true
```

ZIP archive with runnable Eclair node can be found in `<eclair>/eclair-node/target/eclair-node-<release>-bin.zip`.

### 2. Compiling AlarmBot plugin

HC plugin depends on [AlarmBot](https://github.com/engenegr/eclair-alarmbot-plugin) plugin to send out 
custom Telegram messages, and Eclair instance must be run with both of these plugins added, compile AlarmBot as follows:

```
$ cd <eclair-alarmbot-plugin>
$ mvn clean install
```

JAR file can be found in `<eclair-alarmbot-plugin>/target` folder.  

### 3. Compiling Hosted Channels plugin

- Unzip `<eclair>/eclair-node/target/eclair-node-<release>-bin.zip`, copy its `lib` folder into `<plugin-hosted-channels>/lib/`

```
$ cp <eclair>/eclair-core/target/eclair-core_2.13-<release>-tests.jar <plugin-hosted-channels>/lib/eclair-core_2.13-<release>-tests.jar
$ cp <eclair-alarmbot-plugin>/target/eclair-alarmbot_2.13-<release>.jar <plugin-hosted-channels>/lib/eclair-alarmbot_2.13-<release>.jar
$ cd <plugin-hosted-channels>
$ sbt
sbt$ assembly
```

JAR file can be found in `<plugin-hosted-channels>/target` folder.

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

## Provability and preimage broadcast

A distinguishing feature of HC is that although its balance can not be enforced on chain it can be cryptographically
proved at all times so issues can be resolved in standard ways and scam attempts are clearly visible by everyone.

First thing which can be proved is HC balance excluding in-flight HTLCs: each state update in HC contains balance distribution and gets
cross-signed by both channel sides while having a monotonically increasing counter. Thus, any side of channel can claim that its
cross-signed state is the latest one, the other side can refute this claim by providing a cross-signed update with a higher counter
value, and they both can keep doing this until one of them fails to provide.

Second thing which can be proved is in-flight payment owner, but this one is more involved and may require an action to be taken. 
Any HTLC can be seen as the following contract: either receiving side can provide a secret preimage before certain block height and get the money or
fail to do that, in which case money returns to sending side. This enables an adversarial condition where reciving side reveals a preimage but
sender refuses to update channel state such that it clears an in-flight HTLC and assigns payment value to receiver. 

In normal channels this is resolved by receiver force-closing a channel and using a preimage to obtain an in-flight payment on chain. 
In HC this is impossible, but receiver still can prove at a later time that he had revealed a preimage before HTLC expiry by embedding 
a preimage into blockchain by means of `OP_RETURN`. 

This works as follows: after preimage for incoming HTLC has been revealed but channel state has not been updated for a number of blocks, 
and well before HTLC expiry a receiver would get a Telegram warning. After that receiver may broadcast an `OP_RETURN` transaction with a 
preimage using `hc-broadcastpreimages` API call. At a later time receiver will be able to demonstrate the latest cross-signed HC state
with HTLC in-flight and a transaction with related preimage existing in a blockchain before HTLC expiry block.

## Public HCs

Any pair of nodes can establish an HC and declare it public. By doing this HC routing parameters get gossiped to other HC-supporting nodes
which form a parallel routing graph consisiting only of public HCs, which then in turn gets merged with normal channel graph. 
An effect of this is increased network liquidity and more routed payments.

There are 3 requirements which both public HC nodes must meet:
- Each node must have and keep maintaining at least 5 normal public channels.
- Each pair of nodes can have only one public HC declared between them.
- Each node can have at most 2 public HCs declared.

Failure to meet any of these requirements will make a public HC ignored by other nodes which follow these rules.

## Running Eclair with HC plugin

Once Eclair node has in-flight payments in HCs it must always be launched with HC plugin after restarts, otherwise money loss may happen.
If one wishes to stop using an HC plugin then one should first make sure there are no in-flight HTLCs left in HCs (`hc-hot` and `channels` API methods
are helpful here to determine which HTLCs are still pending in both HCs and normal channels).

HC plugin `0.6.2` can only work with Eclair `0.6.2`, HC plugin updates are expected to be released shortly after base Eclair releases.
