package com.github.sakserv.minicluster.util;

import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class WindowsLibsUtils {

    // Logger
    private static final Logger LOG = LoggerFactory.getLogger(WindowsLibsUtils.class);

    public static void setHadoopHome() {

        // Set hadoop.home.dir to point to the windows lib dir
        if (System.getProperty("os.name").startsWith("Windows")) {

            String windowsLibDir = getHadoopHome();

            LOG.info("WINDOWS: Setting hadoop.home.dir: {}", windowsLibDir);
            System.setProperty("hadoop.home.dir", windowsLibDir);
            System.load(new File(windowsLibDir + Path.SEPARATOR + "bin" + Path.SEPARATOR + "hadoop.dll").getAbsolutePath());
            System.load(new File(windowsLibDir + Path.SEPARATOR + "bin" + Path.SEPARATOR + "hdfs.dll").getAbsolutePath());

        }
    }

    public static String getHadoopHome() {

        LOG.info("HADOOP_HOME: " + System.getenv("HADOOP_HOME"));

        if(System.getenv("HADOOP_HOME") != null) {
            return System.getenv("HADOOP_HOME");
        } else {
            if (System.getProperty("HADOOP_HOME") != null) {
                return System.getProperty("HADOOP_HOME");
            } else {
                File windowsLibDir = new File("." + Path.SEPARATOR + "windows_libs" +
                        Path.SEPARATOR + System.getProperty("hdp.release.version"));

                if (!windowsLibDir.exists()) {
                    windowsLibDir = new File(".." + Path.SEPARATOR + windowsLibDir);
                    if (!windowsLibDir.exists()) {
                        LOG.error("WINDOWS: ERROR: Could not find windows native libs");
                    }
                }
                return windowsLibDir.getAbsolutePath();
            }
        }

    }

}
