package org.apache.maven.plugins.shade.mojo;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.shade.Shader;
import org.apache.maven.plugins.shade.filter.SimpleFilter;
import org.apache.maven.plugins.shade.pom.PomWriter;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.IOUtil;

/**
 * Mojo that performs shading delegating to the Shader component.
 *
 * @author Jason van Zyl
 * @author Mauro Talevi
 * @author David Blevins
 * @goal shade
 * @phase package
 * @requiresDependencyResolution runtime
 */
public class ShadeMojo
    extends AbstractMojo
{
    /**
     * @parameter expression="${project}"
     * @readonly
     */
    private MavenProject project;

    /** @component */
    private MavenProjectHelper projectHelper;

    /** @component */
    private Shader shader;

    /**
     * Remote repositories which will be searched for source attachments.
     *
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     */
    protected List remoteArtifactRepositories;

    /**
     * Local maven repository.
     *
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;

    /**
     * Artifact factory, needed to download source jars for inclusion in classpath.
     *
     * @component role="org.apache.maven.artifact.factory.ArtifactFactory"
     * @required
     * @readonly
     */
    protected ArtifactFactory artifactFactory;

    /**
     * Artifact resolver, needed to download source jars for inclusion in classpath.
     *
     * @component role="org.apache.maven.artifact.resolver.ArtifactResolver"
     * @required
     * @readonly
     */
    protected ArtifactResolver artifactResolver;

    /**
     * Artifacts to include/exclude from the final artifact.
     *
     * @parameter
     */
    private ArtifactSet artifactSet;

    /**
     * Packages to be relocated.
     *
     * @parameter
     */
    private PackageRelocation[] relocations;

    /**
     * Resource transformers to be used.
     *
     * @parameter
     */
    private ResourceTransformer[] transformers;

    /**
     * Archive Filters to be used.  Allows you to specify an artifact in the form of
     * groupId:artifactId and a set of include/exclude file patterns for filtering which
     * contents of the archive are added to the shaded jar.  From a logical perspective,
     * includes are processed before excludes, thus it's possible to use an include to
     * collect a set of files from the archive then use excludes to further reduce the set.
     * By default, all files are included and no files are excluded.
     *
     * @parameter
     */
    private ArchiveFilter[] filters;

    /** @parameter expression="${project.build.directory}" */
    private File outputDirectory;

    /**
     * The name of the shaded artifactId
     *
     * @parameter expression="${finalName}"
     */
    private String finalName;

    /**
     * The name of the shaded artifactId. So you may want to use a different artifactId and keep
     * the standard version. If the original artifactId was "foo" then the final artifact would
     * be something like foo-1.0.jar. So if you change the artifactId you might have something
     * like foo-special-1.0.jar.
     *
     * @parameter expression="${shadedArtifactId}" default-value="${project.artifactId}"
     */
    private String shadedArtifactId;

    /**
     * If specified, this will include only artifacts which have groupIds which
     * start with this.
     *
     * @parameter expression="${shadedGroupFilter}"
     */
    private String shadedGroupFilter;

    /**
     * Defines whether the shaded artifact should be attached as classifier to
     * the original artifact.  If false, the shaded jar will be the main artifact
     * of the project
     *
     * @parameter expression="${shadedArtifactAttached}" default-value="false"
     */
    private boolean shadedArtifactAttached;

    /**
     * @parameter expression="${createDependencyReducedPom}" default-value="true"
     */
    private boolean createDependencyReducedPom;

    /**
     * When true, dependencies are kept in the pom but with scope 'provided'; when false,
     * the dependency is removed.
     *
     * @parameter expression="${keepDependenciesWithProvidedScope}" default-value="false"
     */
    private boolean keepDependenciesWithProvidedScope;

    /**
     * When true, transitive deps of removed dependencies are promoted to direct dependencies.
     * This should allow the drop in replacement of the removed deps with the new shaded
     * jar and everything should still work.
     *
     * @parameter expression="${promoteTransitiveDependencies}" default-value="false"
     */
    private boolean promoteTransitiveDependencies;


    /**
     * The name of the classifier used in case the shaded artifact is attached.
     *
     * @parameter expression="${shadedClassifierName}" default-value="shaded"
     */
    private String shadedClassifierName;

    /**
     * When true, it will attempt to create a sources jar as well
     *
     * @parameter expression="${createSourcesJar}" default-value="false"
     */
    private boolean createSourcesJar;


    /** @throws MojoExecutionException  */
    public void execute()
        throws MojoExecutionException
    {
        Set artifacts = new LinkedHashSet();
        Set artifactIds = new LinkedHashSet();
        Set sourceArtifacts = new LinkedHashSet();

        artifacts.add( project.getArtifact().getFile() );

        if ( createSourcesJar )
        {
            File file = shadedSourcesArtifactFile();
            if ( file.exists() )
            {
                sourceArtifacts.add( file );
            }
        }
        
        for ( Iterator it = project.getArtifacts().iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();

            if ( excludeArtifact( artifact ) )
            {
                getLog().info( "Excluding " + artifact.getId() + " from the shaded jar." );

                continue;
            }

            getLog().info( "Including " + artifact.getId() + " in the shaded jar." );

            artifacts.add( artifact.getFile() );

            artifactIds.add( getId( artifact ) );

            if ( createSourcesJar )
            {
                File file = resolveArtifactSources( artifact );
                if ( file != null )
                {
                    sourceArtifacts.add( file );
                }
            }
        }


        File outputJar = shadedArtifactFileWithClassifier();
        File sourcesJar = shadedSourceArtifactFileWithClassifier();

        // Now add our extra resources
        try
        {
            List filters = getFilters();

            List relocators = getRelocators();

            List resourceTransformers = getResourceTrasformers();

            shader.shade( artifacts, outputJar, filters, relocators, resourceTransformers );

            if (createSourcesJar)
            {
                shader.shade( sourceArtifacts, sourcesJar, filters, relocators, resourceTransformers );
            }

            if ( shadedArtifactAttached )
            {
                getLog().info( "Attaching shaded artifact." );
                projectHelper.attachArtifact( getProject(), "jar", shadedClassifierName, outputJar );
                if ( createSourcesJar )
                {
                    projectHelper.attachArtifact( getProject(), "jar",
                                                  shadedClassifierName + "-sources", sourcesJar );
                }
            }

            else
            {
                getLog().info( "Replacing original artifact with shaded artifact." );
                File file = shadedArtifactFile();
                replaceFile( file, outputJar );

                if ( createSourcesJar )
                {
                    file = shadedSourcesArtifactFile();

                    replaceFile( file, sourcesJar );

                    projectHelper.attachArtifact( project, "jar",
                                                  "sources", file );
                }

                if ( createDependencyReducedPom )
                {
                    createDependencyReducedPom( artifactIds );
                }
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error creating shaded jar.", e );
        }
    }

    private void replaceFile(File oldFile, File newFile) throws MojoExecutionException
    {
        getLog().info("Replacing " + oldFile + " with " + newFile);

        File origFile = new File( outputDirectory, "original-" + oldFile.getName() );
        if ( oldFile.exists() && !oldFile.renameTo( origFile ) )
        {
            //try a gc to see if an unclosed stream needs garbage collecting
            System.gc();
            System.gc();

            if ( !oldFile.renameTo( origFile ) )
            {
                // Still didn't work.   We'll do a copy
                try
                {
                    FileOutputStream fout = new FileOutputStream( origFile );
                    FileInputStream fin = new FileInputStream( oldFile );
                    try
                    {
                        IOUtil.copy(fin, fout);
                    }
                    finally
                    {
                        IOUtil.close( fin );
                        IOUtil.close( fout );
                    }
                }
                catch (IOException ex)
                {
                    //kind of ignorable here.   We're just trying to save the original
                    getLog().warn(ex);
                }
            }
        }
        if ( !newFile.renameTo( oldFile ) )
        {
            //try a gc to see if an unclosed stream needs garbage collecting
            System.gc();
            System.gc();
            if ( !newFile.renameTo( oldFile ) )
            {
                // Still didn't work.   We'll do a copy
                try
                {
                    FileOutputStream fout = new FileOutputStream( oldFile );
                    FileInputStream fin = new FileInputStream( newFile );
                    try
                    {
                        IOUtil.copy(fin, fout);
                    }
                    finally
                    {
                        IOUtil.close( fin );
                        IOUtil.close( fout );
                    }
                }
                catch (IOException ex)
                {
                    throw new MojoExecutionException( "Could not replace original artifact with shaded artifact!", ex);
                }
            }
        }
    }

    private File resolveArtifactSources(Artifact artifact) {

        Artifact resolvedArtifact =
            artifactFactory.createArtifactWithClassifier( artifact.getGroupId(),
                                                          artifact.getArtifactId(),
                                                          artifact.getVersion(),
                                                          "java-source",
                                                          "sources");

        try
        {
            artifactResolver.resolve( resolvedArtifact, remoteArtifactRepositories, localRepository );
        }
        catch ( ArtifactNotFoundException e )
        {
            // ignore, the jar has not been found
        }
        catch ( ArtifactResolutionException e )
        {
            getLog().warn( "Could not get sources for " + artifact );
        }

        if ( resolvedArtifact.isResolved() )
        {
            return resolvedArtifact.getFile();
        }
        return null;
    }

    private boolean excludeArtifact( Artifact artifact )
    {
        String id = getId( artifact );

        // This is the case where we have only stated artifacts to include and no exclusions
        // have been listed. We just want what we have asked to include.
        if ( artifactSet != null && ( artifactSet.getExcludes() == null && artifactSet.getIncludes() != null ) && !includedArtifacts().contains( id ) )
        {
            return true;
        }

        if ( excludedArtifacts().contains( id ) )
        {
            return true;
        }

        if ( shadedGroupFilter != null && !artifact.getGroupId().startsWith( shadedGroupFilter ) )
        {
            return true;
        }

        return false;
    }

    private Set excludedArtifacts()
    {
        if ( artifactSet != null && artifactSet.getExcludes() != null )
        {
            return artifactSet.getExcludes();
        }

        return Collections.EMPTY_SET;
    }

    private Set includedArtifacts()
    {
        if ( artifactSet != null && artifactSet.getIncludes() != null )
        {
            return artifactSet.getIncludes();
        }

        return Collections.EMPTY_SET;
    }

    private List getRelocators()
    {
        List relocators = new ArrayList();

        if ( relocations == null )
        {
            return relocators;
        }

        for ( int i = 0; i < relocations.length; i++ )
        {
            PackageRelocation r = relocations[i];

            relocators.add( new SimpleRelocator( r.getPattern(), r.getShadedPattern(), r.getExcludes() ) );

        }
        return relocators;
    }

    private List getResourceTrasformers()
    {
        if ( transformers == null )
        {
            return Collections.EMPTY_LIST;
        }

        return Arrays.asList( transformers );
    }

    private List getFilters()
    {
        List filters = new ArrayList();

        if ( this.filters == null )
        {
            return filters;
        }

        Map artifacts = new HashMap();

        artifacts.put( getId( project.getArtifact() ), project.getArtifact().getFile() );

        for ( Iterator it = project.getArtifacts().iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();

            artifacts.put( getId( artifact ), artifact.getFile() );
        }

        for ( int i = 0; i < this.filters.length; i++ )
        {
            ArchiveFilter f = this.filters[i];

            File jar = (File) artifacts.get( f.getArtifact() );

            if ( jar == null )
            {
                getLog().info( "No artifact matching filter " + f.getArtifact() );

                continue;
            }

            filters.add( new SimpleFilter( jar, f.getIncludes(), f.getExcludes() ) );

        }

        return filters;
    }

    private File shadedArtifactFileWithClassifier()
    {
        Artifact artifact = project.getArtifact();
        final String shadedName =
            shadedArtifactId + "-" + artifact.getVersion() + "-" + shadedClassifierName + "." + artifact.getArtifactHandler().getExtension();
        return new File( outputDirectory, shadedName );
    }
    private File shadedSourceArtifactFileWithClassifier()
    {
        Artifact artifact = project.getArtifact();
        final String shadedName =
            shadedArtifactId + "-" + artifact.getVersion() + "-" + shadedClassifierName + "-sources." + artifact.getArtifactHandler().getExtension();
        return new File( outputDirectory, shadedName );
    }

    private File shadedArtifactFile()
    {
        Artifact artifact = project.getArtifact();

        String shadedName;

        if ( finalName != null )
        {
            shadedName = finalName + "." + artifact.getArtifactHandler().getExtension();
        }
        else
        {
            shadedName = shadedArtifactId + "-" + artifact.getVersion() + "." + artifact.getArtifactHandler().getExtension();
        }

        return new File( outputDirectory, shadedName );
    }
    private File shadedSourcesArtifactFile()
    {
        Artifact artifact = project.getArtifact();

        String shadedName;

        if ( finalName != null )
        {
            shadedName = finalName + "-sources." + artifact.getArtifactHandler().getExtension();
        }
        else
        {
            shadedName = shadedArtifactId + "-" + artifact.getVersion() + "-sources." + artifact.getArtifactHandler().getExtension();
        }

        return new File( outputDirectory, shadedName );
    }

    protected MavenProject getProject()
    {
        if ( project.getExecutionProject() != null )
        {
            return project.getExecutionProject();
        }
        else
        {
            return project;
        }
    }

    // We need to find the direct dependencies that have been included in the uber JAR so that we can modify the
    // POM accordingly.
    private void createDependencyReducedPom( Set artifactsToRemove )
        throws IOException
    {
        Model model = getProject().getOriginalModel();

        List dependencies = new ArrayList();

        boolean modified = false;

        List origDeps = getProject().getDependencies();
        if ( promoteTransitiveDependencies )
        {
            origDeps = new ArrayList();
            for ( Iterator it = project.getArtifacts().iterator(); it.hasNext(); )
            {
                Artifact artifact = (Artifact) it.next();

                //promote
                Dependency dep = new Dependency();
                dep.setArtifactId( artifact.getArtifactId() );
                if (artifact.hasClassifier())
                {
                    dep.setClassifier( artifact.getClassifier() );
                }
                dep.setGroupId( artifact.getGroupId() );
                dep.setOptional( artifact.isOptional() );
                dep.setScope( artifact.getScope() );
                dep.setType( artifact.getType() );
                dep.setVersion( artifact.getVersion() );

                // How to do these?
                //dep.setSystemPath( .... );
                //dep.setExclusions( exclusions );
                origDeps.add( dep );
            }
        }

        for ( Iterator i = origDeps.iterator(); i.hasNext(); )
        {
            Dependency d = (Dependency) i.next();

            dependencies.add( d );

            String id = d.getGroupId() + ":" + d.getArtifactId();

            if ( artifactsToRemove.contains( id ) )
            {
                modified = true;

                if ( keepDependenciesWithProvidedScope )
                {
                    d.setScope( "provided" );
                }
                else
                {
                    dependencies.remove( d );
                }
            }
        }

        // Check to see if we have a reduction and if so rewrite the POM.
        if ( modified )
        {
            model.setDependencies( dependencies );

            File f = new File( outputDirectory, "dependency-reduced-pom.xml" );
            if (f.exists())
            {
                f.delete();
            }

            Writer w = new FileWriter( f );

            PomWriter.write( w, model, true );

            w.close();

            project.setFile( f );
        }
    }

    private String getId( Artifact artifact )
    {
        return artifact.getGroupId() + ":" + artifact.getArtifactId();
    }
}