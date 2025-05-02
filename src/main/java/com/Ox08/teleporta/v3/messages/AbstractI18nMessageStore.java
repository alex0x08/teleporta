/*
 * Copyright 2020 0x08 Software.
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
package com.Ox08.teleporta.v3.messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
/**
 * Custom storage for i18n bundles
 *
 * @author Alex Chernyshev <alex3.145@gmail.com>
 * @since 1.0
 */
public class AbstractI18nMessageStore  {
    // bundle locale
    protected Locale messageLocale = Locale.getDefault();
    // additional bundles, loaded from plugins
    private final List<ResourceBundle> additionalBundles = new ArrayList<>();
    // default bundle name
    private final String defaultBundleName;
    // default bundle
    protected ResourceBundle defaultBundle;
    /**
     * Constructor should be called from children class
     *
     * @param db
     */
    protected AbstractI18nMessageStore(String db) {
        this.defaultBundleName = db;
        reloadMessages();
    }
    // reload default bundle
    final synchronized void reloadMessages() {
        this.defaultBundle
                = ResourceBundle.getBundle(this.defaultBundleName, messageLocale);
    }
    // add & load additional bundle
    public void addBundle(String name) {
        this.addBundle(
                ResourceBundle.getBundle(name, messageLocale));
    }
    public synchronized void addBundle(ResourceBundle r) {
        this.additionalBundles.add(r);
    }
    public Locale getErrorLocale() {
        return messageLocale;
    }
    public void setErrorLocale(Locale locale) {
        this.messageLocale = locale;
        this.reloadMessages();
    }
    protected String formatMessage(String raw, Object... args) {
        return String.format(raw,  args);
    }
    /**
     * get formatted text by key, with lookup in additional bundles
     *
     * @param key
     * @return
     */
    protected String getMessage(String key) {
        if (this.defaultBundle.containsKey(key))
            return this.defaultBundle.getString(key);

        for (ResourceBundle r : this.additionalBundles)
            if (r.containsKey(key))
                return r.getString(key);
        return null;
    }
}
