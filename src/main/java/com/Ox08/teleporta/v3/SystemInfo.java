/*
 * Copyright 2022 0x08 Software.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.messages.TeleportaError;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
/**
 * Stores build info
 * @author 0x08
 * @since 1.0
 */
public class SystemInfo {
    /**
     * build.version=${application.version}
     * build.time=${application.build.timestamp}
     * build.number=${buildNumber}
     */
    private String buildVersion, buildTime, buildNum;
    private boolean wasLoaded;
    public String getBuildVersion() {
        return buildVersion;
    }
    public String getBuildTime() {
        return buildTime;
    }
    public String getBuildNum() {
        return buildNum;
    }
    public synchronized void load() {
        if (wasLoaded) {
            throw TeleportaError.withError(0x7270);
        }
        try (InputStream in = getClass().getResourceAsStream("/release.properties")) {
            final Properties p = new Properties();
            p.load(in);
            this.buildNum = p.getProperty("build.number", "unknown");
            this.buildTime = p.getProperty("build.time", "unknown");
            this.buildVersion = p.getProperty("build.version", "unknown");
            wasLoaded = true;
        } catch (IOException e) {
            throw TeleportaError.withError(0x7271,e);
        }
    }
    public static final SystemInfo SI = new SystemInfo();
}
