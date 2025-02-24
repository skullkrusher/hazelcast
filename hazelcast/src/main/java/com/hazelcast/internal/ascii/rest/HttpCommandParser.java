/*
 * Copyright (c) 2008-2024, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.ascii.rest;

import com.hazelcast.internal.ascii.CommandParser;
import com.hazelcast.internal.ascii.TextCommand;
import com.hazelcast.internal.ascii.memcache.ErrorCommand;
import com.hazelcast.internal.nio.ascii.TextDecoder;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.StringTokenizer;

import static com.hazelcast.internal.ascii.TextCommandConstants.TextCommandType.ERROR_CLIENT;
import static com.hazelcast.internal.ascii.TextCommandConstants.TextCommandType.ERROR_SERVER;

abstract class HttpCommandParser<HC extends HttpCommand> implements CommandParser {

    @Override
    public final TextCommand parser(TextDecoder decoder, String cmd, int space) {
        StringTokenizer st = new StringTokenizer(cmd);
        st.nextToken();
        String uri;
        if (st.hasMoreTokens()) {
            uri = st.nextToken();
        } else {
            return new ErrorCommand(ERROR_CLIENT);
        }
        try {
            String urlDecodedUri = URLDecoder.decode(uri, "UTF-8");
            return createHttpCommand(decoder, urlDecodedUri);
        } catch (UnsupportedEncodingException e) {
            return new ErrorCommand(ERROR_SERVER, "UTF-8 encoding is not supported by JVM!");
        }
    }

    abstract HC createHttpCommand(TextDecoder decoder, String uri);
}
