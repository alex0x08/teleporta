# Teleporta
Teleporting files from Bob to Alice since 2015. Community edition.

This is our internal tool, dedicated to fast&secure file exchange in a team.



Article with detailed project description (Russian): [https://blog.0x08.ru/teleporta](https://blog.0x08.ru/teleporta)

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



# In action

![Sample 1](https://github.com/alex0x08/teleporta/blob/main/images/screen1.gif?raw=true)
![Sample 2](https://github.com/alex0x08/teleporta/blob/main/images/screen2.gif?raw=true)
![Sample 3](https://github.com/alex0x08/teleporta/blob/main/images/screen3.gif?raw=true)
