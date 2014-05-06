/**********************************************************************
Copyright (c) 2007 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
 **********************************************************************/
package org.datanucleus.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.Scanner;
import org.codehaus.plexus.util.SelectorUtils;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.Commandline;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * Base for all enhancer-based Maven2 goals.
 */
public abstract class AbstractEnhancerMojo extends AbstractDataNucleusMojo
{

    private static final String PERSISTENCE_UNIT_SOURCE_FILE = "persistenceUnitSourceFile";

    private static final String PERSISTENCE_UNIT_METADATA_INCLUDES = "persistenceUnitMetadataIncludes";

    private static final String TOOL_NAME_DATANUCLEUS_ENHANCER = "org.datanucleus.enhancer.DataNucleusEnhancer";

    private static final String[] NO_FILTER = {};

    /**
     * The project currently being build.
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * @parameter expression="${quiet}" default-value="false"
     */
    protected boolean quiet;

    /**
     * @parameter expression="${alwaysDetachable}" default-value="false"
     */
    protected boolean alwaysDetachable;

    /**
     * @parameter expression="${generatePK}" default-value="true"
     */
    protected boolean generatePK;

    /**
     * @parameter expression="${generateConstructor}" default-value="true"
     */
    protected boolean generateConstructor;

    /**
     * @parameter expression="${detachListener}" default-value="false"
     */
    protected boolean detachListener;

    /**
     * @parameter expression="${useFileListFile}" default-value="auto"
     */
    protected String useFileListFile;

    /**
     * Method to execute the enhancer using the provided artifacts and input files.
     * @param pluginArtifacts Artifacts to use in CLASSPATH generation
     * @param files Input files
     */
    @Override
    protected void executeDataNucleusTool(List pluginArtifacts, List files)
        throws CommandLineException, MojoExecutionException
    {
        enhance(pluginArtifacts, files);
    }

    /**
     * Find the metadata files that should be passed to the enhancer with support for incremental builds.
     * The Maven incremental build support handles resources that are copied by the maven-resource-plugin in a different manner than class
     * files, it doesn't track changes to the target resources since they are copied to the output directory, so we need to detect
     * change on the source resource instead. See {@link BuildContext#newScanner(File, boolean)} for more details.
     * Therefore when running incremental builds that have mapping metadata files we check the delta on the resource's source folders - see
     * {@link #scanSourceResources(MetadataIncludes)}.
     */
    @Override
    protected List<File> findMetadataFiles() throws MojoExecutionException
    {
        final List<File> metadataFiles = new ArrayList<>();

        getLog().info("################################");
        getLog().info(" Build Context is: " + buildContext);
        getLog().info(" Metadata dir is: " + metadataDirectory);
        getLog().info(" Incremental build: " + buildContext.isIncremental());
        getLog().info(" Persistence Unit: " + persistenceUnitName);
        getLog().info("################################");

        if (buildContext.isIncremental())
        {
            MetadataIncludes includes = determineIncludes();

            if (includes.hasMappingFiles())
            {
                List<File> mappingFiles = scanSourceResources(includes.getMappingFiles(), includes.isIgnoreDelta());

                if (!mappingFiles.isEmpty())
                {
                    // Find the classes name for the classes in the in the mapping files
                    List<String> mappingFilesClassesName = listClassesName(mappingFiles);

                    getLog().info("Dependent classes for metadata file: " + mappingFilesClassesName);

                    File baseDirFile = new File(project.getBuild().getSourceDirectory());
                    String[] listSourceFilesName = listSourceFilesName(baseDirFile, mappingFilesClassesName);

                    if (listSourceFilesName.length == 0)
                    {
                        // No source found, so classes must be from a dependency and available in the classpath. Remove the existing
                        // enhanced classes and try to enhance based on the mapping files

                        // FIXME Should be the target dir
                        File targetDir = new File(project.getBuild().getOutputDirectory());
                        clean(targetDir, mappingFilesClassesName);

                        metadataFiles.addAll(mappingFiles);
                    }
                    else
                    {
                        // / Force it to recompile so we can enhance it
                        touch(baseDirFile, listSourceFilesName);

                        // Done, nothing to enhance in this run
                        return Collections.emptyList();
                    }
                }
            }

            // If there is no mapping files defined or none of them has changed
            if (metadataFiles.isEmpty())
            {
                // Scan for actual source class changes
                metadataFiles.addAll(scanMetadataFiles(metadataDirectory, includes.getClasses(), includes.isIgnoreDelta()));
            }
        }
        else if (hasPersitenceUnit())
        {
            // No need to scan since it's a full build and the enhancement will be based on the persistence.xml, however we still need to
            // return a non-empty result otherwise the base class won't proceed with the enhancement. We are arbitrarily returning a
            // reference to the XML file although it won't be used.
            metadataFiles.add(getPersistenceUnitXMLFile());
        }
        else
        {
            // Otherwise just use regular results
            metadataFiles.addAll(super.findMetadataFiles());
        }

        getLog().info(" ==> All found files: " + metadataFiles);

        return metadataFiles;
    }

    private void clean(File targetDir, List<String> mappingFilesClassesName)
    {
        for (String className : mappingFilesClassesName)
        {
            String classFileName = convertClassNameToFilePath(className, ".class");
            File classFile = new File(targetDir, classFileName);
            getLog().info("Deleting " + classFile);
            classFile.delete();
        }
    }

    private void touch(File baseDirFile, String[] sourceFiles)
    {
        getLog().info("Forcing recompile for files: " + Arrays.toString(sourceFiles));

        long now = new Date().getTime();
        for (String fileName : sourceFiles)
        {
            File sourceFile = new File(baseDirFile, fileName);
            getLog().info("Refreshing: " + sourceFile);
            sourceFile.setLastModified(now);
            buildContext.refresh(sourceFile);
        }
    }

    private String[] listSourceFilesName(File baseDirFile, List<String> mappingFilesClassNames)
    {
        getLog().info("Searching for source files at: " + baseDirFile);
        Scanner scanner = buildContext.newScanner(baseDirFile, true);
        String[] includes = new String[mappingFilesClassNames.size()];
        int i = 0;
        for (String className : mappingFilesClassNames)
        {
            includes[i++] = convertClassNameToFilePath(className, ".java");
        }

        getLog().info("With includes: " + Arrays.toString(includes));
        scanner.setIncludes(includes);
        scanner.scan();

        return scanner.getIncludedFiles();
    }

    private List<String> listClassesName(List<File> mappingFiles) throws MojoExecutionException
    {
        List<String> classesNames = new ArrayList<>();

        for (File mappingFile : mappingFiles)
        {
            // TODO Support JPA mapping file
            classesNames.addAll(parseMappingFile(mappingFile));
        }

        return classesNames;
    }

    /**
     * It must scan all resources's sources folders since we don't know where the mapping files that end up on the metadataDirectory
     * are coming from. So we only scan resource dirs where the target is the metadataDirectory.
     * @param filters
     */
    private List<File> scanSourceResources(String[] filters, boolean ignoreDelta) throws MojoExecutionException
    {
        List<Resource> allResources = new ArrayList<>(project.getResources());
        allResources.addAll(project.getTestResources());

        String outputDirectory = project.getBuild().getOutputDirectory();

        List<File> metadataFiles = new ArrayList<>();

        for (Resource resource : allResources)
        {
            String targetPath = resource.getTargetPath() == null ? outputDirectory : resource.getTargetPath();

            if (targetPath.contains(metadataDirectory.getPath()))
            {
                File dir = new File(resource.getDirectory());

                if (dir.exists())
                {
                    String[] incls = resource.getIncludes().toArray(new String[resource.getIncludes().size()]);

                    // Convert it to target file so that if it relies on resource filtering/replacement it works as well.
                    List<File> targetFiles = toTarget(scanMetadataFiles(dir, incls, filters, ignoreDelta), targetPath);
                    metadataFiles.addAll(targetFiles);
                }
            }
        }

        return metadataFiles;
    }

    private List<File> toTarget(List<File> scanMetadataFiles, String targetPath)
    {
        // TODO Convert to source files to their respective target file
        return scanMetadataFiles;
    }

    private List<File> scanMetadataFiles(File directory, String[] includes, boolean ignoreDelta) throws MojoExecutionException
    {
        return scanMetadataFiles(directory, includes, NO_FILTER, ignoreDelta);
    }

    /**
     * Returns the metadata files, with support for incremental builds.
     * In a incremental build it will return only files that have been changed, otherwise all the files are returned.
     * If a persistence unit is being used it will restrict the files according to the persistence unit specification.
     * @param includes
     * @param directory
     */
    private List<File> scanMetadataFiles(File directory, String[] includes, String[] filters, boolean ignoreDelta)
        throws MojoExecutionException
    {
        getLog().info("Scanning directory: " + directory);
        getLog().info("Includes: " + Arrays.toString(includes));
        getLog().info("Filters: " + Arrays.toString(filters));

        Scanner scanner = buildContext.newScanner(directory, ignoreDelta);
        scanner.setIncludes(includes);
        // TODO Support excludes

        long startTime = System.currentTimeMillis();
        scanner.scan();

        List<File> metadataFiles = new ArrayList<>();

        String[] includedFiles = scanner.getIncludedFiles();
        if (includedFiles != null)
        {
            for (String includedFile : includedFiles)
            {
                if (matchFilters(includedFile, filters))
                {
                    File modelFile = new File(scanner.getBasedir(), includedFile);
                    metadataFiles.add(modelFile);
                }
            }
        }

        getLog().info("Files found: " + metadataFiles);

        long endTime = System.currentTimeMillis();
        getLog().info("Scan completed in " + (endTime - startTime) + " ms.");

        return metadataFiles;
    }

    private boolean matchFilters(String includedFile, String[] filters)
    {
        boolean matches = false;

        if (filters.length == 0)
        {
            matches = true;
        }
        else
        {
            for (String pattern : filters)
            {
                if (SelectorUtils.matchPath(pattern, includedFile))
                {
                    matches = true;
                    break;
                }
            }
        }

        return matches;
    }

    private MetadataIncludes determineIncludes() throws MojoExecutionException
    {
        MetadataIncludes includes;

        if (hasPersitenceUnit())
        {
            includes = getPersistenceUnitIncludes();
        }
        else
        {
            includes = getConfigMetadataIncludes();
        }

        return includes;
    }

    private MetadataIncludes getPersistenceUnitIncludes() throws MojoExecutionException
    {
        MetadataIncludes includes = (MetadataIncludes) buildContext.getValue(PERSISTENCE_UNIT_METADATA_INCLUDES);

        if (isPersistenceUnitChange())
        {
            // Read new persistence unit and create a partial MetadataInclude with only the new entries
            MetadataIncludes newIncludes = readIncludesFromPersistenceUnit();
            MetadataIncludes diffIncludes = MetadataIncludes.diff(includes, newIncludes);

            // Cache the new metadata files
            buildContext.setValue(PERSISTENCE_UNIT_METADATA_INCLUDES, newIncludes);

            // Return the diff for this build
            includes = diffIncludes;
        }
        else if (includes == null)
        {
            // Create a new one and cache it
            includes = readIncludesFromPersistenceUnit();
            buildContext.setValue(PERSISTENCE_UNIT_METADATA_INCLUDES, includes);
        }

        return includes;
    }

    private boolean isPersistenceUnitChange() throws MojoExecutionException
    {
        File puSourceFile = findPersistenceUnitSourceFile();

        Scanner scanner = buildContext.newScanner(puSourceFile);
        scanner.scan();

        return scanner.getIncludedFiles().length > 0;
    }

    private File findPersistenceUnitSourceFile() throws MojoExecutionException
    {
        File puSourceFile = (File) buildContext.getValue(PERSISTENCE_UNIT_SOURCE_FILE);

        if (puSourceFile == null || !puSourceFile.exists())
        {
            // Find the peristence.xml inside the project folders, excluding the output folder because we want the *source* resource file.
            List<File> files;
            try
            {
                // e.g. "/target/classes/**"
                String exclude = project.getBuild().getOutputDirectory().replace(project.getBasedir().getPath(), "") + "/**";
                files = FileUtils.getFiles(project.getBasedir(), "**/persistence.xml", exclude);

                switch (files.size())
                {
                    case 1 :
                        puSourceFile = files.get(0);
                        break;

                    case 0 :
                        throw new MojoExecutionException(
                                "No persistence.xml found in the project yet there is a persistence unit name defined: " + persistenceUnitName);

                    default :
                        throw new MojoExecutionException(
                                "More than one persistence.xml found in the project: " + files);
                }
            }
            catch (IOException e)
            {
                throw new MojoExecutionException("Error trying to find persistence.xml file", e);
            }
        }

        buildContext.setValue(PERSISTENCE_UNIT_SOURCE_FILE, puSourceFile);

        return puSourceFile;
    }

    private MetadataIncludes getConfigMetadataIncludes()
    {
        // TODO cache this
        List<String> classes = new ArrayList<>();
        List<String> mappingFiles = new ArrayList<>();

        for (String include : metadataIncludes.split(","))
        {
            if (include.endsWith(".class"))
            {
                classes.add(include);
            }
            else
            {
                // Everything else is considered mapping files
                mappingFiles.add(include);
            }
        }

        return new MetadataIncludes(mappingFiles.toArray(new String[mappingFiles.size()]), classes.toArray(new String[classes.size()]));
    }

    private boolean hasPersitenceUnit()
    {
        return StringUtils.isNotBlank(persistenceUnitName);
    }

    private MetadataIncludes readIncludesFromPersistenceUnit() throws MojoExecutionException
    {
        getLog().debug("Reading files to include from persistence unit");

        File puFile = getPersistenceUnitXMLFile();
        PersistenceUnitConfig puConfig = parsePersistenceUnitFile(puFile);
        MetadataIncludes includes;

        if (puConfig.isExcludeUnlistedClasses())
        {
            List<String> classMetadataFiles = puConfig.getClassMetadataFiles();
            List<String> resourceMetadataFiles = puConfig.getMappingMetadataFiles();
            includes = new MetadataIncludes(
                    resourceMetadataFiles.toArray(new String[resourceMetadataFiles.size()]),
                    classMetadataFiles.toArray(new String[classMetadataFiles.size()]));
        }
        else
        {
            getLog().debug("No exclude-unlisted-classes found, will read all metadata files");
            includes = getConfigMetadataIncludes();
        }

        return includes;
    }

    private File getPersistenceUnitXMLFile()
    {
        return new File(project.getBuild().getOutputDirectory(), "META-INF/persistence.xml");
    }

    private List<String> parseMappingFile(File mappingFile) throws MojoExecutionException
    {
        List<String> classes = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader reader = null;
        String currentPackage = null;
        try (FileInputStream inputStream = new FileInputStream(mappingFile))
        {
            reader = factory.createXMLStreamReader(inputStream);
            while (reader.hasNext())
            {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT)
                {
                    switch (reader.getLocalName())
                    {
                        case "package" :
                            currentPackage = reader.getAttributeValue(null, "name");
                            break;

                        case "class" :
                            if (currentPackage == null)
                            {
                                getLog().info("Unable to parse file " + mappingFile + " in order to determine classes");
                            }
                            else
                            {
                                String className = reader.getAttributeValue(null, "name");
                                classes.add(currentPackage + "." + className);
                            }
                            break;

                        default :
                            // Ignore content
                    }
                }
            }

            return classes;
        }
        catch (Exception e)
        {
            getLog().error(e.getMessage());
            throw new MojoExecutionException("Error trying to parse " + mappingFile, e);
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (XMLStreamException e)
                {
                    getLog().error("Error closing stream", e);
                }
            }
        }
    }

    private PersistenceUnitConfig parsePersistenceUnitFile(File persistenceUnitFile) throws MojoExecutionException
    {
        List<String> classMetadataFiles = new ArrayList<>();
        boolean excludeUnlistedClasses = false;

        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader reader = null;
        try (FileInputStream inputStream = new FileInputStream(persistenceUnitFile))
        {
            reader = factory.createXMLStreamReader(inputStream);
            while (reader.hasNext())
            {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT)
                {
                    switch (reader.getLocalName())
                    {
                        case "class" :
                            classMetadataFiles.add(convertClassNameToFilePath(reader.getElementText(), ".class"));
                            break;

                        case "exclude-unlisted-classes" :
                            excludeUnlistedClasses = true;
                            break;

                        // TODO Support mapping files
                        default :
                            // Ignore content
                    }
                }
            }

            return new PersistenceUnitConfig(excludeUnlistedClasses, classMetadataFiles, Collections.<String> emptyList());
        }
        catch (Exception e)
        {
            getLog().error(e.getMessage());
            throw new MojoExecutionException("Error trying to parse persistence.xml", e);
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (XMLStreamException e)
                {
                    getLog().error("Error closing stream", e);
                }
            }
        }
    }

    private String convertClassNameToFilePath(String elementText, String extension)
    {
        return elementText.replace('.', File.separatorChar) + extension;
    }

    /**
     * Run the DataNucleus Enhancer using the specified input data.
     * @param pluginArtifacts for creating classpath for execution.
     * @param files input file list
     * @throws CommandLineException if there was an error invoking the DataNucleus Enhancer.
     * @throws MojoExecutionException
     */
    protected void enhance(List pluginArtifacts, List files)
        throws CommandLineException, MojoExecutionException
    {
        // Generate a set of CLASSPATH entries (avoiding dups)
        // Put plugin deps first so they are found before any project-specific artifacts
        List cpEntries = new ArrayList();
        for (Iterator it = pluginArtifacts.iterator(); it.hasNext();)
        {
            Artifact artifact = (Artifact) it.next();
            try
            {
                String artifactPath = artifact.getFile().getCanonicalPath();
                if (!cpEntries.contains(artifactPath))
                {
                    cpEntries.add(artifactPath);
                }
            }
            catch (IOException e)
            {
                throw new MojoExecutionException("Error while creating the canonical path for '" + artifact.getFile() + "'.", e);
            }
        }
        Iterator uniqueIter = getUniqueClasspathElements().iterator();
        while (uniqueIter.hasNext())
        {
            String entry = (String) uniqueIter.next();
            if (!cpEntries.contains(entry))
            {
                cpEntries.add(entry);
            }
        }

        // Set the CLASSPATH of the java process
        StringBuffer cpBuffer = new StringBuffer();
        for (Iterator it = cpEntries.iterator(); it.hasNext();)
        {
            cpBuffer.append((String) it.next());
            if (it.hasNext())
            {
                cpBuffer.append(File.pathSeparator);
            }
        }

        if (fork)
        {
            // Create a CommandLine for execution
            Commandline cl = new Commandline();
            cl.setExecutable("java");

            // uncomment the following if you want to debug the enhancer
            // cl.addArguments(new String[]{"-Xdebug", "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000"});

            cl.createArg().setValue("-cp");
            cl.createArg().setValue(cpBuffer.toString());

            // Logging - check for Log4j, else JDK1.4
            URL log4jURL = getLog4JConfiguration();
            if (log4jURL != null)
            {
                cl.createArg().setValue("-Dlog4j.configuration=" + log4jURL);
            }
            else
            {
                URL jdkLogURL = getJdkLogConfiguration();
                if (jdkLogURL != null)
                {
                    cl.createArg().setValue("-Djava.util.logging.config.file=" + jdkLogURL);
                }
            }

            cl.createArg().setValue(TOOL_NAME_DATANUCLEUS_ENHANCER);

            // allow extensions to prepare Mode specific arguments
            prepareModeSpecificCommandLineArguments(cl, null);

            if (quiet)
            {
                cl.createArg().setValue("-q");
            }
            else if (verbose)
            {
                cl.createArg().setValue("-v");
            }

            boolean usingPU = false;
            if (persistenceUnitName != null && persistenceUnitName.trim().length() > 0)
            {
                usingPU = true;
                cl.createArg().setLine("-pu " + persistenceUnitName);
            }

            cl.createArg().setLine("-api " + api);

            if (alwaysDetachable)
            {
                cl.createArg().setValue("-alwaysDetachable");
            }

            if (!generatePK)
            {
                cl.createArg().setLine("-generatePK false");
            }

            if (!generateConstructor)
            {
                cl.createArg().setLine("-generateConstructor false");
            }

            if (detachListener)
            {
                cl.createArg().setLine("-detachListener true");
            }

            if (!usingPU)
            {
                if (determineUseFileListFile())
                {
                    File fileListFile = writeFileListFile(files);
                    cl.createArg().setLine("-flf \"" + fileListFile.getAbsolutePath() + '"');
                }
                else
                {
                    for (Iterator it = files.iterator(); it.hasNext();)
                    {
                        File file = (File) it.next();
                        cl.createArg().setValue(file.getAbsolutePath());
                    }
                }
            }

            executeCommandLine(cl);
        }
        else
        {
            // Execute in the current JVM, so build up list of arguments to the method invoke
            List args = new ArrayList();

            // allow extensions to prepare Mode specific arguments
            prepareModeSpecificCommandLineArguments(null, args);

            if (quiet)
            {
                args.add("-q");
            }
            else if (verbose)
            {
                args.add("-v");
            }
            // FIXME Incremental build should not use OU
            boolean usingPU = false;
            if (persistenceUnitName != null && persistenceUnitName.trim().length() > 0)
            {
                usingPU = true;
                args.add("-pu");
                args.add(persistenceUnitName);
            }

            args.add("-api");
            args.add(api);

            if (alwaysDetachable)
            {
                args.add("-alwaysDetachable");
            }

            if (!generatePK)
            {
                args.add("-generatePK");
                args.add("false");
            }

            if (!generateConstructor)
            {
                args.add("-generateConstructor");
                args.add("false");
            }

            if (detachListener)
            {
                args.add("-detachListener");
                args.add("true");
            }

            if (!usingPU)
            {
                for (Iterator it = files.iterator(); it.hasNext();)
                {
                    File file = (File) it.next();
                    args.add(file.getAbsolutePath());
                }
            }

            executeInJvm(TOOL_NAME_DATANUCLEUS_ENHANCER, args, cpEntries, quiet);
        }

        // FIXME Should be the target dir
        buildContext.refresh(new File(project.getBuild().getOutputDirectory()));
    }

    /**
     * Template method that sets up arguments for the enhancer depending upon the <b>mode</b> invoked.
     * This is expected to be implemented by extensions.
     * @param cl {@link Commandline} instance to set up arguments for.
     * @param args Arguments list generated by this call (appended to)
     */
    protected abstract void prepareModeSpecificCommandLineArguments(Commandline cl, List args);

    /**
     * Accessor for the name of the class to be invoked.
     * @return Class name for the Enhancer.
     */
    @Override
    protected String getToolName()
    {
        return TOOL_NAME_DATANUCLEUS_ENHANCER;
    }

    protected boolean determineUseFileListFile()
    {
        if (useFileListFile != null)
        {
            if ("true".equalsIgnoreCase(useFileListFile))
            {
                return true;
            }
            else if ("false".equalsIgnoreCase(useFileListFile))
            {
                return false;
            }
            else if (!"auto".equalsIgnoreCase(useFileListFile))
            {
                System.err.println("WARNING: useFileListFile is an unknown value! Falling back to default!");
            }
        }
        // 'auto' means true on Windows and false on other systems. Maybe we'll change this in the
        // future to always be true as the command line might be limited on other systems, too.
        // See: http://www.cyberciti.biz/faq/argument-list-too-long-error-solution/
        // See: http://serverfault.com/questions/69430/what-is-the-maximum-length-of-a-command-line-in-mac-os-x
        return File.separatorChar == '\\';
    }

    /**
     * Writes the given {@code files} into a temporary file. The file is deleted by the enhancer.
     * @param files the list of files to be written into the file (UTF-8-encoded). Must not be
     * <code>null</code>.
     * @return the temporary file.
     */
    private static File writeFileListFile(Collection<File> files)
    {
        try
        {
            File fileListFile = File.createTempFile("enhancer-", ".flf");
            System.out.println("Writing fileListFile: " + fileListFile);
            FileOutputStream out = new FileOutputStream(fileListFile);
            try
            {
                OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
                try
                {
                    for (File file : files)
                    {
                        w.write(file.getAbsolutePath());
                        // The enhancer uses a BufferedReader, which accepts all types of line feeds (CR, LF,
                        // CRLF).
                        // Therefore a single \n is fine.
                        w.write('\n');
                    }
                }
                finally
                {
                    w.close();
                }
            }
            finally
            {
                out.close();
            }
            return fileListFile;
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static class PersistenceUnitConfig
    {
        private final boolean excludeUnlistedClasses;

        private final List<String> classMetadataFiles;

        private final List<String> mappingMetadataFiles;

        private PersistenceUnitConfig(boolean excludeUnlistedClasses, List<String> classMetadataFiles, List<String> mappingMetadataFiles)
        {
            this.excludeUnlistedClasses = excludeUnlistedClasses;
            this.classMetadataFiles = classMetadataFiles;
            this.mappingMetadataFiles = mappingMetadataFiles;
        }

        public boolean isExcludeUnlistedClasses()
        {
            return excludeUnlistedClasses;
        }

        public List<String> getClassMetadataFiles()
        {
            return classMetadataFiles;
        }

        public List<String> getMappingMetadataFiles()
        {
            return mappingMetadataFiles;
        }
    }

    /**
     * Metadata includes separated by mapping files and classes
     */
    private static class MetadataIncludes
    {
        private final String[] mappingFiles;

        private final String[] classes;

        private final boolean ignoreDelta;

        public MetadataIncludes(String[] mappingFiles, String[] classes)
        {
            this(mappingFiles, classes, false);
        }

        public MetadataIncludes(String[] mappingFiles, String[] classes, boolean ignoreDelta)
        {
            super();
            this.mappingFiles = mappingFiles;
            this.classes = classes; // TODO When no classes specified default to *.class so we can track changes for mapping files
            this.ignoreDelta = ignoreDelta;
        }

        public boolean isIgnoreDelta()
        {
            return ignoreDelta;
        }

        public boolean hasMappingFiles()
        {
            return mappingFiles.length > 0;
        }

        public String[] getMappingFiles()
        {
            return mappingFiles;
        }

        public String[] getClasses()
        {
            return classes;
        }

        public static MetadataIncludes diff(MetadataIncludes includes, MetadataIncludes newIncludes)
        {
            MetadataIncludes includesDiff;

            if (includes == null)
            {
                includesDiff = new MetadataIncludes(newIncludes.getMappingFiles(), newIncludes.getClasses(), true);
            }
            else
            {
                String[] diffMappingFiles = diff(includes.getMappingFiles(), newIncludes.getMappingFiles());
                String[] diffClasses = diff(includes.getClasses(), newIncludes.getClasses());
                includesDiff = new MetadataIncludes(diffMappingFiles, diffClasses, true);
            }

            return includesDiff;
        }

        private static String[] diff(String[] includes, String[] newIncludes)
        {
            Set<String> includesSet = new HashSet<>(Arrays.asList(includes));
            Set<String> diffIncludesSet = new HashSet<>(Arrays.asList(newIncludes));
            diffIncludesSet.removeAll(includesSet);

            return (String[]) diffIncludesSet.toArray(new String[diffIncludesSet.size()]);
        }
    }
}
