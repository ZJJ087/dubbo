/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common;

import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_UNEXPECTED_EXCEPTION;

/**
 * Version
 */
public final class Version {
    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(Version.class);

    private static final Pattern PREFIX_DIGITS_PATTERN = Pattern.compile("^([0-9]*).*");

    // Dubbo RPC protocol version, for compatibility, it must not be between 2.0.10 ~ 2.6.2
    public static final String DEFAULT_DUBBO_PROTOCOL_VERSION = "2.0.2";
    // version 1.0.0 represents Dubbo rpc protocol before v2.6.2
    public static final int LEGACY_DUBBO_PROTOCOL_VERSION = 10000; // 1.0.0
    // Dubbo implementation version.
    private static String VERSION;
    private static String LATEST_COMMIT_ID;

    /**
     * For protocol compatibility purpose.
     * Because {@link #isSupportResponseAttachment} is checked for every call, int compare expect to has higher
     * performance than string.
     */
    public static final int LOWEST_VERSION_FOR_RESPONSE_ATTACHMENT = 2000200; // 2.0.2
    public static final int HIGHEST_PROTOCOL_VERSION = 2009900; // 2.0.99
    private static final Map<String, Integer> VERSION2INT = new HashMap<String, Integer>();

    static {
        // check if there's duplicated jar
        Version.checkDuplicate(Version.class);

        // get dubbo version and last commit id
        try {
            Properties properties =
                ConfigUtils.loadProperties(Collections.emptySet(), "META-INF/version");

            VERSION = Optional.ofNullable(properties.getProperty("revision"))
                .filter(StringUtils::isNotBlank)
                .orElseGet(() -> getVersion(Version.class, ""));
            LATEST_COMMIT_ID = Optional.ofNullable(properties.getProperty("git.commit.id")).orElse("");
        } catch (Throwable e) {
            logger.warn(COMMON_UNEXPECTED_EXCEPTION, "", "", "continue the old logic, ignore exception " + e.getMessage(), e);
            VERSION = getVersion(Version.class, "");
            LATEST_COMMIT_ID = "";
        }
    }

    private Version() {
    }

    public static String getProtocolVersion() {
        return DEFAULT_DUBBO_PROTOCOL_VERSION;
    }

    public static String getVersion() {
        return VERSION;
    }

    public static String getLastCommitId() {
        return LATEST_COMMIT_ID;
    }

    /**
     * Compare versions
     *
     * @return the value {@code 0} if {@code version1 == version2};
     * a value less than {@code 0} if {@code version1 < version2}; and
     * a value greater than {@code 0} if {@code version1 > version2}
     */
    public static int compare(String version1, String version2) {
        return Integer.compare(getIntVersion(version1), getIntVersion(version2));
    }

    /**
     * Check the framework release version number to decide if it's 2.7.0 or higher
     */
    public static boolean isRelease270OrHigher(String version) {
        if (StringUtils.isEmpty(version)) {
            return false;
        }

        return getIntVersion(version) >= 2070000;
    }

    /**
     * Check the framework release version number to decide if it's 2.6.3 or higher
     *
     * @param version, the sdk version
     */
    public static boolean isRelease263OrHigher(String version) {
        return getIntVersion(version) >= 2060300;
    }

    /**
     * Dubbo 2.x protocol version numbers are limited to 2.0.2/2000200 ~ 2.0.99/2009900, other versions are consider as
     * invalid or not from official release.
     *
     * @param version, the protocol version.
     * @return
     */
    public static boolean isSupportResponseAttachment(String version) {
        if (StringUtils.isEmpty(version)) {
            return false;
        }
        int iVersion = getIntVersion(version);
        if (iVersion >= LOWEST_VERSION_FOR_RESPONSE_ATTACHMENT && iVersion <= HIGHEST_PROTOCOL_VERSION) {
            return true;
        }

        return false;
    }

    public static int getIntVersion(String version) {
        Integer v = VERSION2INT.get(version);
        if (v == null) {
            try {
                v = parseInt(version);
                // e.g., version number 2.6.3 will convert to 2060300
                if (version.split("\\.").length == 3) {
                    v = v * 100;
                }
            } catch (Exception e) {
                logger.warn(COMMON_UNEXPECTED_EXCEPTION, "", "", "Please make sure your version value has the right format: " +
                    "\n 1. only contains digital number: 2.0.0; \n 2. with string suffix: 2.6.7-stable. " +
                    "\nIf you are using Dubbo before v2.6.2, the version value is the same with the jar version.");
                v = LEGACY_DUBBO_PROTOCOL_VERSION;
            }
            VERSION2INT.put(version, v);
        }
        return v;
    }

    private static int parseInt(String version) {
        int v = 0;
        String[] vArr = version.split("\\.");
        int len = vArr.length;
        for (int i = 0; i < len; i++) {
            String subV = getPrefixDigits(vArr[i]);
            if (StringUtils.isNotEmpty(subV)) {
                v += Integer.parseInt(subV) * Math.pow(10, (len - i - 1) * 2);
            }
        }
        return v;
    }

    /**
     * get prefix digits from given version string
     */
    private static String getPrefixDigits(String v) {
        Matcher matcher = PREFIX_DIGITS_PATTERN.matcher(v);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    public static String getVersion(Class<?> cls, String defaultVersion) {
        try {
            // find version info from MANIFEST.MF first
            Package pkg = cls.getPackage();
            String version = null;
            if (pkg != null) {
                version = pkg.getImplementationVersion();
                if (StringUtils.isNotEmpty(version)) {
                    return version;
                }

                version = pkg.getSpecificationVersion();
                if (StringUtils.isNotEmpty(version)) {
                    return version;
                }
            }

            // guess version from jar file name if nothing's found from MANIFEST.MF
            CodeSource codeSource = cls.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                logger.info("No codeSource for class " + cls.getName() + " when getVersion, use default version " + defaultVersion);
                return defaultVersion;
            }

            URL location = codeSource.getLocation();
            if (location == null) {
                logger.info("No location for class " + cls.getName() + " when getVersion, use default version " + defaultVersion);
                return defaultVersion;
            }
            String file = location.getFile();
            if (!StringUtils.isEmpty(file) && file.endsWith(".jar")) {
                version = getFromFile(file);
            }

            // return default version if no version info is found
            return StringUtils.isEmpty(version) ? defaultVersion : version;
        } catch (Throwable e) {
            // return default version when any exception is thrown
            logger.error(COMMON_UNEXPECTED_EXCEPTION, "", "", "return default version, ignore exception " + e.getMessage(), e);
            return defaultVersion;
        }
    }

    /**
     * get version from file: path/to/group-module-x.y.z.jar, returns x.y.z
     */
    private static String getFromFile(String file) {
        // remove suffix ".jar": "path/to/group-module-x.y.z"
        file = file.substring(0, file.length() - 4);

        // remove path: "group-module-x.y.z"
        int i = file.lastIndexOf('/');
        if (i >= 0) {
            file = file.substring(i + 1);
        }

        // remove group: "module-x.y.z"
        i = file.indexOf("-");
        if (i >= 0) {
            file = file.substring(i + 1);
        }

        // remove module: "x.y.z"
        while (file.length() > 0 && !Character.isDigit(file.charAt(0))) {
            i = file.indexOf("-");
            if (i >= 0) {
                file = file.substring(i + 1);
            } else {
                break;
            }
        }
        return file;
    }

    public static void checkDuplicate(Class<?> cls, boolean failOnError) {
        checkDuplicate(cls.getName().replace('.', '/') + ".class", failOnError);
    }

    public static void checkDuplicate(Class<?> cls) {
        checkDuplicate(cls, false);
    }

    public static void checkDuplicate(String path, boolean failOnError) {
        try {
            // search in caller's classloader
            Set<String> files = getResources(path);
            // duplicated jar is found
            if (files.size() > 1) {
                String error = "Duplicate class " + path + " in " + files.size() + " jar " + files;
                if (failOnError) {
                    throw new IllegalStateException(error);
                } else {
                    logger.error(COMMON_UNEXPECTED_EXCEPTION, "", "", error);
                }
            }
        } catch (Throwable e) {
            logger.error(COMMON_UNEXPECTED_EXCEPTION, "", "", e.getMessage(), e);
        }
    }

    /**
     * search resources in caller's classloader
     */
    private static Set<String> getResources(String path) throws IOException {
        Enumeration<URL> urls = ClassUtils.getCallerClassLoader(Version.class).getResources(path);
        Set<String> files = new HashSet<String>();
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (url != null) {
                String file = url.getFile();
                if (StringUtils.isNotEmpty(file)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

}
