package com.indeed.vw.wrapper.jni;

import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple library class for working with JNI (Java Native Interface),
 * see <a href="http://adamheinrich.com/blog/2012/how-to-load-native-jni-library-from-jar/">here.
 *
 * @author Adam Heirnich &lt;adam@adamh.cz&gt;, <a href="http://www.adamh.cz">http://www.adamh.cz
 * @author Jon Morra
 */
public class NativeUtils {
    private static final Logger logger = Logger.getLogger(NativeUtils.class);
    /**
     * Private constructor - this class will never be instanced
     */
    private NativeUtils() {
    }

    // Identifies which OS distributions can share binaries
    private static final Map<String, List<String>> identicalOSs = new HashMap<>();
    static {
        // RedHat binaries
        final List<String> redHatAlternatives = new ArrayList<>();
        redHatAlternatives.add("Red_Hat.6");
        identicalOSs.put("Scientific.6", redHatAlternatives);
    }

    /**
     * Shell call to lsb_release to get OS info
     * @param lsb_args parameters to lsb_release.  EX: "-i" or "-a"
     * @param regexp a regular expression pattern used to parse the response from lsb_release
     *   For example:  lsb_args = "-i", regexp = "Distributor ID: *(.*)$"
     * @return A system dependent string identifying the OS.
     * @throws IOException If an error occurs while running shell command
     */
    private static String lsbRelease(final String lsb_args, final Pattern regexp) throws IOException {
        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec("lsb_release " + lsb_args);
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            Matcher matcher;
            while ((line = reader.readLine()) != null) {
                matcher = regexp.matcher(line);
                if (matcher.matches()) {
                    return matcher.group(1);
                }
            }
        }
        finally {
            reader.close();
        }
        return null;
    }

    /**
     * Attempt to find the Linux distribution ID.
     * @return The Linux distribution or null if the version cannot be found.
     * @throws IOException If an I/O error occurs
     */
    private static String getDistroName() throws IOException {
        return lsbRelease("-i", Pattern.compile("Distributor ID:\\s*(.*)\\s*$"));
    }

    /**
     * This will attempt to find the Linux version by making use of {@code lsb_release -r}
     * @return The Linux version or null if the version cannot be determined.
     * @throws IOException If an I/O error occurs
     */
    private static String getLinuxVersion() throws IOException {
        return lsbRelease("-r", Pattern.compile("Release:\\s*(.*)\\s*$"));
    }

   /**
     * Returns the system dependent OS family.  In the case of a Linux OS it will combine
     * {@link NativeUtils#getDistroName()} and {@link NativeUtils#getLinuxVersion()}.
     * @return A list system dependent string identifying the OS.  This is a list because there are some OSs which are
     * functionally identical to the one returned by lsbRelease.
     * @throws UnsupportedEncodingException If an error occurs while determining the Linux specific
     *          information.
     * @throws IOException If an I/O error occurs
     * @throws IllegalStateException If the os.name property returns an unsupported OS.
     */
   private static List<String> getOsFamilies() throws IOException {
        final String osName = System.getProperty("os.name");
        final String primaryName;
        if (osName.toLowerCase().contains("mac")) {
            primaryName = "Darwin";
        }
        else if (osName.toLowerCase().contains("linux")) {
            final String distro = getDistroName();
            if (distro == null) {
                throw new UnsupportedEncodingException("Cannot determine linux distribution");
            }
            final String version = getLinuxVersion();
            if (version == null) {
                throw new UnsupportedOperationException("Cannot determine linux version");
            }
            // get the major version.
            // don't expect a period because linux version might not have one
            primaryName = distro + "." + version.split("\\.")[0];
        }
        else {
            throw new IllegalStateException("Unsupported operating system " + osName);
        }

        final List<String> viableFamilies = new ArrayList<>();
        viableFamilies.add(primaryName);
        if (identicalOSs.containsKey(primaryName)) {
            viableFamilies.addAll(identicalOSs.get(primaryName));
        }
        return viableFamilies;
    }

    /**
     * Loads a library from current JAR archive by looking up platform dependent name.
     * @param path The filename inside JAR as absolute path (beginning with '/'), e.g. /package/File.ext
     * @param suffix The suffix to be appended to the name
     * @throws UnsupportedEncodingException If an error occurs while determining the Linux specific
     *          information.
     * @throws IOException If temporary file creation or read/write operation fails
     */
    public static void loadOSDependentLibrary(final String path, final String suffix) throws IOException {
        final List<String> osFamilies = getOsFamilies();
        String osDependentLib = null;
        for (final String osFamily : osFamilies) {
            final String currentOsDependentLib = path + "." + osFamily + suffix;
            if (NativeUtils.class.getResource(currentOsDependentLib) != null) {
                osDependentLib = currentOsDependentLib;
                break;
            }
        }
        if (osDependentLib != null) {
            logger.info("Loading " + osDependentLib);
            System.out.println("Loading " + osDependentLib);
            loadLibraryFromJar(osDependentLib);
        }
        else {
            try {
                logger.info("Loading " + path + suffix);
                System.out.println("Loading " + path + suffix);
                loadLibraryFromJar(path + suffix);
            }
            catch (final FileNotFoundException e) {
                // If we cannot find an OS dependent library then try and load a library in a system independent fashion.
                System.loadLibrary(path.replaceFirst("/", ""));
            }
        }
    }

    /**
     * Loads library from current JAR archive
     *
     * The file from JAR is copied into system temporary directory and then loaded. The temporary file is deleted after exiting.
     * Method uses String as filename because the pathname is "abstract", not system-dependent.
     *
     * @param path The filename inside JAR as absolute path (beginning with '/'), e.g. /package/File.ext
     * @throws IOException If temporary file creation or read/write operation fails
     * @throws IllegalArgumentException If source file (param path) does not exist
     * @throws IllegalArgumentException If the path is not absolute or if the filename is shorter than three characters (restriction of {@link File#createTempFile(java.lang.String, java.lang.String)}).
     */
    public static void loadLibraryFromJar(final String path) throws IOException {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("The path has to be absolute (start with '/').");
        }

        // Obtain filename from path
        String[] parts = path.split("/");
        final String filename = (parts.length > 1) ? parts[parts.length - 1] : null;

        // Split filename to prefix and suffix (extension)
        String prefix = "";
        String suffix = null;
        if (filename != null) {
            parts = filename.split("\\.", 2);
            prefix = parts[0];
            suffix = (parts.length > 1) ? "." + parts[parts.length - 1] : null; // Thanks, davs! :-)
        }

        // Check if the filename is okay
        if (filename == null || prefix.length() < 3) {
            throw new IllegalArgumentException("The filename has to be at least 3 characters long.");
        }

        // Prepare temporary file
        final File temp = File.createTempFile(prefix, suffix);
        temp.deleteOnExit();

        if (!temp.exists()) {
            throw new FileNotFoundException("File " + temp.getAbsolutePath() + " does not exist.");
        }

        // Prepare buffer for data copying
        final byte[] buffer = new byte[1024];
        int readBytes;

        // Open and check input stream
        final InputStream is = NativeUtils.class.getResourceAsStream(path);
        if (is == null) {
            throw new FileNotFoundException("File " + path + " was not found inside JAR.");
        }

        // Open output stream and copy data between source file in JAR and the temporary file
        OutputStream os = new FileOutputStream(temp);
        try {
            while ((readBytes = is.read(buffer)) != -1) {
                os.write(buffer, 0, readBytes);
            }
        }
        finally {
            // If read/write fails, close streams safely before throwing an exception
            os.close();
            is.close();
        }

        // Finally, load the library
        System.load(temp.getAbsolutePath());

        final String libraryPrefix = prefix;
        final String lockSuffix = ".lock";

        // create lock file
        final File lock = new File(temp.getAbsolutePath() + lockSuffix);
        lock.createNewFile();
        lock.deleteOnExit();

        // file filter for library file (without .lock files)
        final FileFilter tmpDirFilter = new FileFilter() {
            public boolean accept(final File pathname) {
                return pathname.getName().startsWith(libraryPrefix) && !pathname.getName().endsWith(lockSuffix);
            }
        };

        // get all library files from temp folder
        final String tmpDirName = System.getProperty("java.io.tmpdir");
        final File tmpDir = new File(tmpDirName);
        final File[] tmpFiles = tmpDir.listFiles(tmpDirFilter);

        // delete all files which don't have n accompanying lock file
        for (final File tmpFile : tmpFiles) {
            // Create a file to represent the lock and test.
            final File lockFile = new File(tmpFile.getAbsolutePath() + lockSuffix);
            if (!lockFile.exists()) {
                tmpFile.delete();
            }
        }
    }
}
