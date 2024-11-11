# Teleporta
Teleporting files from Bob to Alice since 2015. Community edition.

This is our internal tool, dedicated to fast&secure file exchange in a team.

Article with detailed project description (Russian): [https://blog.0x08.ru/teleporta](https://blog.0x08.ru/teleporta)

# How it work
Techincally Teleporta has 2 different applications combined in one and enabled by command line arguments.

There are two work modes: 'relay' (server) and 'portal' (client).  

![Schema](https://github.com/alex0x08/teleporta/blob/main/images/teleporta-schema.png?raw=true)

In 'relay' mode, Teleporta start to operate as relay: app will start embedded HTTP-server, which accepts incoming requests. 
Then another Teleporta instance but in 'portal' mode, registers on that relay and uploads files and download them on another side.

![Relay mode](https://github.com/alex0x08/teleporta/blob/main/images/teleporta-relay-mode.png?raw=true)


In 'portal' mode, Teleporta connects to relay using provided url, registers self on that relay and start to monitor special local folders for changes. 

Each file or folder found in that folders will be automatically transferred to remote machine via relay.


![Portal mode](https://github.com/alex0x08/teleporta/blob/main/images/teleporta-portal.png?raw=true)


# How to run

Just download  latest `teleporta.cmd` from releases and run in console. 
If you on Windows and don't have any JDK or JRE installed - scipt will download it automatically, for all other OS - please verify that you have Java 1.8+ installed, could be JRE or JDK.

To start in relay mode:
```
teleporta.cmd -relay
```
After start threre would be a long url displayed, like `http://majestic12:8989/2d52fb71ef728d8813a001a6592c8248801d844ce2c0d0a6976f10b73d3bdb463ea4cd09c1ad9a25d5b83a543238`

You need to copy it and paste as first argument to start Teleporta in 'portal' mode:
```
teleporta.cmd http://majestic12:8989/2d52fb71ef728d8813a001a6592c8248801d844ce2c0d0a6976f10b73d3bdb463ea4cd09c1ad9a25d5b83a543238
```

It's possible to run both relay and portal on same machine, useful for testing:

![Both Portal and relay on same machine](https://github.com/alex0x08/teleporta/blob/main/images/teleporta-both.png?raw=true)


# In action

![Sample 1](https://github.com/alex0x08/teleporta/blob/main/images/screen1.gif?raw=true)
![Sample 2](https://github.com/alex0x08/teleporta/blob/main/images/screen2.gif?raw=true)
![Sample 3](https://github.com/alex0x08/teleporta/blob/main/images/screen3.gif?raw=true)

# Cryptography

Each portal has its own pair of keys (public&private) used for file encryption. Public key is shared throught relay with all other connected portals and used when one relay send file to another.
Let's say portal 'Bob' wants to transfer file thought relay to portal 'Alice'. Bob takes Alice's public key from relay and use it to encrypt file which need to be transferred.
When received, Alice decrypts file using own private key.


The community version uses weak algorihms: 2048bit RSA and 128bit AES, fair enough for normal users, but easy breakable by any 'special forces', so please don't try to use this tool for anything illegal.

# Additional options
There are additional settings, could be passed to Teleporta by commonly used -Dparameter=value mechanism.

Enable debug output:
```
-DappDebug=true
```
Enable 'programmatic' file watcher instead of native:
```
-DdumbWatcher=true
```
Useful for old or network filesystems, like on Windows 98 as shown upper.

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

Display startup logo:
```
-DshowLogo=false
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

# Technical details
This project passed long way, full of pain and issues, what you see there is 3rd *Java* version and each version has been rewritten from scratch. 
There were also Teleporta versions in C++ and Golang, which not survived: C++ has too many standards nowadays and Golang has serious issues with legacy environments.

To be more specific, we faced that using C++ 11 as most widely used modern C++ version requires us to use and support weird hacks like [this](https://github.com/gulrak/filesystem) if we going to provide long support.
More recent C++ 17 and especially 20 are not well supported in common compilers, and it's better to use older compiler, if you need stability on wide range of environments.
Same story for Golang: it sucks on legacy, even 10 years old Linux/Windows is a problem.

# How to build

Teleporta has no dependencies and can be easily build by any JDK 1.8 or upper with Apache Maven:

```
mvn clean package
```
Final executable `teleporta.cmd` could be found in `target` folder.

If you prefer to use plain old JAR, without all bootstrap magic - just take executable .JAR file from same `target` folder.

