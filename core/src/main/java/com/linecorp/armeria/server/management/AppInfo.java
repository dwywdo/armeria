/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License
 */

package com.linecorp.armeria.server.management;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A class that represents application information, which can be configured through
 * {@link ServerBuilder#setAppInfo(AppInfo)}.
 */
public final class AppInfo {
    @Nullable final String version;
    @Nullable final String name;
    @Nullable final String description;

    /**
     * Creates a new {@link AppInfo} that holds information about an application.
     * @param version A version of an application e.g. "1.0.0"
     * @param name A name of an application
     * @param description A description of application
     */
    public AppInfo(@Nullable String version, @Nullable String name, @Nullable String description) {
        this.version = version;
        this.name = name;
        this.description = description;
    }

    @JsonProperty
    public String getVersion() {
        return version;
    }

    @JsonProperty
    public String getName() {
        return name;
    }

    @JsonProperty
    public String getDescription() {
        return description;
    }
}
