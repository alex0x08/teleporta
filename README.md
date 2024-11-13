# Teleporta
Teleporting files from Bob to Alice since 2015. Community edition.

This is our internal tool, dedicated to fast and secure file exchange within a team.

An article with detailed project description could be found here (in Russian): [https://blog.0x08.ru/teleporta](https://blog.0x08.ru/teleporta)

# How it work
Technically, Teleporta has 2 different applications combined in one and enabled by command line arguments.

There are two work modes:'relay' (server) and 'portal' (client).  

![Schema](https://github.com/alex0x08/teleporta/blob/main/images/teleporta-schema.png?raw=true)

In'relay' mode, Teleporta starts to operate as a relay: the application will start an embedded HTTP server, which accepts incoming requests. 

Then another Teleporta instance, but in 'portal' mode, registers on that relay and uploads files and downloads them on another side.

![Relay mode](https://github.com/alex0x08/teleporta/blob/main/images/teleporta-relay-mode.png?raw=true)


In 'portal' mode, Teleporta connects to the relay using the provided URL, registers itself on that relay, and starts to monitor special local folders for changes. 

Each file or folder found in those folders will be automatically transferred to the remote machine via relay.


![Portal mode](https://github.com/alex0x08/teleporta/blob/main/images/teleporta-portal.png?raw=true)


# How to run

Just download  latest `teleporta.cmd` from releases and run in console. 
If you're on Windows and don't have any JDK or JRE installed - the startup script will try to download it automatically. For all other OSes, please verify that you have Java 1.8+ installed, which could be JRE or JDK.


To start in relay mode:
```
teleporta.cmd -relay
```
After the start, there would be a long URL displayed, like

`http://majestic12:8989/2d52fb71ef728d8813a001a6592c8248801d844ce2c0d0a6976f10b73d3bdb463ea4cd09c1ad9a25d5b83a543238`

You need to copy it and paste it as the first argument to start Teleporta in 'portal' mode:

```
teleporta.cmd http://majestic12:8989/2d52fb71ef728d8813a001a6592c8248801d844ce2c0d0a6976f10b73d3bdb463ea4cd09c1ad9a25d5b83a543238
```

It's possible to run both relay and portal on the same machine, which is useful for testing:

![Both Portal and relay on same machine](https://github.com/alex0x08/teleporta/blob/main/images/teleporta-both.png?raw=true)


# In action

![Sample 1](https://github.com/alex0x08/teleporta/blob/main/images/screen1.gif?raw=true)
![Sample 2](https://github.com/alex0x08/teleporta/blob/main/images/screen2.gif?raw=true)
![Sample 3](https://github.com/alex0x08/teleporta/blob/main/images/screen3.gif?raw=true)

# Cryptography

Each portal has its own pair of keys (public & private), used for file encryption.

The public key is shared by the relay with all other connected portals and is used when one relay sends a file to another.

Let's say portal 'Bob' wants to transfer a file through relay to portal 'Alice.' Bob takes Alice's public key from the relay and uses it to encrypt the file that needs to be transferred. When received, Alice decrypts the file using her own private key.

The community version uses weak algorithms: 2048-bit RSA and 128-bit AES, fair enough for normal users, but easily breakable by any 'special forces,' so please don't try to use this tool for anything illegal.

# Additional options
There are additional settings that could be passed to Teleporta by the commonly used -Dparameter=value mechanism.

Enable debug output:
```
-DappDebug=true
```
Enable 'programmatic' file watcher instead of native:
```
-DdumbWatcher=true
```
Useful for slow or legacy or network filesystems, like on Windows 98, as shown above.

Don't generate seed and use static instead:
```
-Dseed=testseed
```
If provided, context path will match provided seed, like: `http://majestic12:8989/testseed`

Allow portal update even if its already registered:
```
-DallowPortalNameUpdate=true
```

Provide own portal name instead of hostname:
```
-DportalName=samplePortal
```
Use a template for portal name:
```
-DportalNameTemplate="USERNAME from HOSTNAME"
```
Keywords USERNAME and HOSTNAME will be substituted by actual username and hostname, then the final string will be used as portal name.

Display startup logo:
```
-DshowLogo=false
```
Custom MOTD (Message of the day), that displayed when portal connects:
```
-Dmotd="This is my awesome relay!"
```

Create link on Desktop to Teleporta's folders:  
```
-DcreateDesktopLink=false
```
Specify custom storage path:
```
-DappHome=/full/path/to/folder
```

Specify listening port (8989 by default):
```
-DappPort=9000
```

Delete non-delivered files on start:
```
-DclearOutgoing=true
```
This works both on relay and portal sides, but should be enabled separately.
When enabled, Teleporta will delete all non-delivered files, otherwise - will try to deliver them.
Disabled by default.

Disable outgoing:
```
-DallowOutgoing=false
```
When set, portal will not be able to send anything, all outgoing logic will be disabled completely.


# Private relays

Since 3.1.1 we added a new 'private relay' mode, which allows to run relays privately: clients will not be able to connect without local key file. 
  
Pass argument `-DprivateRelay=true` when start Teleporta relay, grab key from console, which look like this:
```
|TELEPORTA30820122300d06092a864886f70d01010105000382010f003082010a0282010100b04b
a71d7f0a7ce1dc9359a8e90c63971904105d443303a171e6622c8ba14121aba9e09a748293b31a65
076dda3d58237783978a8c490a714516607aca2f68577e59b707bd53b4dcfe26ed7221769081d76f
3af7b5554eb3a6f2e653a5092109a35f963d52fdf23b6978f3e273cbf95f716d12e13db380cd9688
340cc3cd00ca61a730b38e7ecbed0436bf7e86d6eee75c89515f730ad7001d41ebc42ba7b0d46a58
2d3215be71cbbd246b52f7f0c12c642c00d16b1e3617326c0c24b15057aa4c89fb345af4c54fe4a3
954750164291a2d2c8c0aa10f86db1935722d1ec80104dc139b4fe1810d678e5a2ca8af2368c4452
be458a6606eca8331386ef625e050203010001|
``` 
Save it to text file and provide it when start Teleporta in client mode:
```
-DrelayKey=/opt/work/tmp/tele.pub
```

# Self download

Since 3.1.2 we added important feature that simplifies deployment: 

Teleporta Relay now allows to download the 'self package' - generated zip-archive with application and config file with defined relay url.

Just open link in browser on client side, where you need to deploy Teleporta: 
```
 Public Teleporta Relay started: http://amarok:8989/4d9575d00ace270aa168c8d043a99984c0ba4807043719b4629461f31cf828d30645
```
And you'll have ready to use client deployment! Just unpack and run.

To turn this amazing feature off, pass this command line argument:
```
-DselfDownload=false
```


# Manual triggering and the 'lock' file 

Trying to copy some huge folder with lot of nested folders to outgoing could produce incorrect results: Teleporta will start to pack it before copying process completes.  
Sad news, but there is no easy way to monitor whole folder hierarchy (without serious performance impact), so when Teleporta receives file/folder creation event - it does not know if there are no running processes on inner folders. 

To solve that, we added special mode called `useLockFile` (disabled by default),  which could be enabled by command line argument:
```
-DuseLockFile=true
```
When enabled, all copy/move actions inside 'to' folder will trigger creation of special file called 'lock' . Till this file exists - there would be no uploading process.

So you need to remove this file by hand, right after all required files/folders are copied. 
Removing this file will trigger uploading, but till its not done - folder will not be monitored for changes.


# Technical details
This project has come a long way, full of pain and issues; what you see there is the 3rd *Java* version, and each version has been rewritten from scratch. 
There were also Teleporta versions in C++ and Golang, which did not survive: C++ has too many standards nowadays, and Golang has serious issues with legacy environments.

To be more specific, we faced the fact that using C++ 11 as the most widely used modern C++ version requires us to use and support weird hacks like [this](https://github.com/gulrak/filesystem) if we going to provide long support.
More recent C++ versions, especially 20, are not well supported in common compilers, and it's better to use older compilers if you need stability in a wide range of environments. Same story for Golang: it sucks on legacy; even 10-year-old Linux/Windows hosts are a big problem.

# How to build

Teleporta has no dependencies and can be easily built by any JDK 1.8 or upper with Apache Maven:

```
mvn clean package
```
The final executable `teleporta.cmd`, could be found in the `target` folder.

If you prefer to use plain old JAR, without all the bootstrap magic, just take the executable JAR from the same `target` folder.
