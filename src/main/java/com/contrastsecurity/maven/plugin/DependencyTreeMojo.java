package com.contrastsecurity.maven.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;

import com.contrastsecurity.exceptions.ApplicationCreateException;
import com.contrastsecurity.exceptions.UnauthorizedException;
import com.contrastsecurity.models.AgentType;
import com.contrastsecurity.models.Application;
import com.contrastsecurity.models.dtm.ApplicationCreateRequest;
import com.contrastsecurity.models.oss.dependencytree.DependencyDetails;
import com.contrastsecurity.models.oss.dependencytree.DependencyTreeBody;
import com.contrastsecurity.models.oss.dependencytree.JavaContainer;
import com.contrastsecurity.models.oss.dependencytree.Snapshot;
import com.contrastsecurity.sdk.ContrastSDK;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;

import lombok.Getter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Submits dependency tree information about this project to Contrast.
 *
 * @since 2.13
 */
@Mojo(name = "dependency-tree", aggregator = true , requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyCollection = ResolutionScope.TEST, requiresOnline = true, defaultPhase = LifecyclePhase.TEST)
public class DependencyTreeMojo extends AbstractContrastMavenPluginMojo {
    /**
     * The Maven execution session.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Contains the full list of projects in the reactor.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    /**
     * The dependency tree builder to use.
     */
    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    /**
     * Version of this plugin from pom.xml to send to the server.
     */
    @Parameter(defaultValue = "mvn-${plugin.version}", readonly = true)
    private String pluginVersion;

    /**
     * True if goal should create a new application if one does not already exist with the specified name.
     * Defaults to true.
     */
    @Parameter(property = "contrast.createApplicationIfNeeded")
    protected boolean createApplicationIfNeeded = true;

    final private String DEPENDENCY_TREE_URL_FORMAT = "%s://%s/Contrast/static/ng/index.html#/%s/applications/%s/libs/dependency-tree";

    public void execute() throws MojoExecutionException {
        ContrastSDK sdk = connectToTeamServer();
        ensureApplication(sdk);

        HashMap<String, Map<String, DependencyDetails>> output = new HashMap<>(reactorProjects.size());
        try {
            for (MavenProject project : reactorProjects) {
                String artifactId = project.getArtifactId();
                getLog().debug("Entering project " + artifactId);

                ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
                buildingRequest.setProject(project);

                ContrastDependencyTreeVisitor contrastVisitor = new ContrastDependencyTreeVisitor(getLog());
                DependencyNodeVisitor visitor = new BuildingDependencyNodeVisitor(contrastVisitor);
                DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null, reactorProjects);

                rootNode.accept(visitor);
                output.putAll(contrastVisitor.getTree());

                getLog().debug("Exiting project " + artifactId);
            }
        } catch (DependencyGraphBuilderException exception) {
            throw new MojoExecutionException("Cannot build project dependency graph.", exception);
        }

        DependencyTreeBody body = new DependencyTreeBody(appId, pluginVersion, new Snapshot(new JavaContainer(output)));

        if (getLog().isDebugEnabled()) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try {
                File buildDirectory = new File(project.getBuild().getDirectory());
                buildDirectory.mkdirs();
                FileWriter writer = new FileWriter(new File(buildDirectory, "contrast-dependency-tree.json"));
                gson.toJson(body, writer);
                writer.close();
                getLog().debug("Wrote JSON dependency tree body to target/contrast-dependency-tree.json");
            } catch (JsonIOException | IOException e) {
                getLog().debug("Unable to serialize/write JSON for debugging.", e);
            }
        }

        try {
            sdk.createDependencyTreeSnapshot(orgUuid, appId, body);
        } catch (IOException | UnauthorizedException e) {
            throw new MojoExecutionException("Contrast dependency tree submission failed.", e);
        }

        getLog().info("Contrast dependency tree snapshot submitted successfully.");
        getLog().info("View it at: " + formatDependencyTreeUrl());
    }

    private void ensureApplication(ContrastSDK sdk) throws MojoExecutionException {
        verifyAppIdOrNameNotBlank();

        if (appId != null) {
            try {
                sdk.getApplication(orgUuid, appId);
            } catch (IOException | UnauthorizedException e) {
                throw new MojoExecutionException("Unable to find application with ID " + appId, e);
            }
        }

        //if appId is null here, we must have an application name so try to retrieve the ID
        if (appId == null) {
            try {
                Application application = sdk.getApplicationByNameAndLanguage(super.orgUuid, super.appName, AgentType.JAVA);
                if (application != null) {
                    appId = application.getId();
                } else {
                    appId = null;
                }
            } catch (IOException | UnauthorizedException e) {
                throw new MojoExecutionException("Unable to list applications.", e);
            }
        }

        //if appId is still null try to create a new Java application with the name specified, if configuration allows, else exit
        if (appId == null) {
            if (createApplicationIfNeeded) {
                getLog().info(String.format("Attempting to create application '%s' (%s)", appName, AgentType.JAVA));
                try {
                    Application application = sdk.createApplication(super.orgUuid, new ApplicationCreateRequest(appName, AgentType.JAVA));
                    appId = application.getId();
                    getLog().info(String.format("Created application with ID %s", appId));
                } catch (IOException | UnauthorizedException | ApplicationCreateException e) {
                    throw new MojoExecutionException("Creation of application failed.", e);
                }
            } else {
                throw new MojoExecutionException("No application found named '" + appName + "' and createApplicationIfNeeded=false");
            }
        }
    }

    private String formatDependencyTreeUrl() {
        URL url = null;
        try {
            url = new URL(super.apiUrl);
        } catch (MalformedURLException e) {
            //code elsewhere should have validated the URL so this is unreachable
            //skip logging of dependency tree URL if this fails somehow
        }
        if (url != null) {
            String hostPort = formatUrlHostPort(url);
            return String.format(DEPENDENCY_TREE_URL_FORMAT, url.getProtocol(), hostPort, super.orgUuid, appId);
        }

        return String.format(DEPENDENCY_TREE_URL_FORMAT, "https", "your_teamserver", super.orgUuid, appId);
    }

    private String formatUrlHostPort(URL url) {
        if ((url.getProtocol().equalsIgnoreCase("http") && url.getPort() != 80) ||
            (url.getProtocol().equalsIgnoreCase("https") && url.getPort() != 443)
        ) {
            //include port if it is non standard for the protocol
            return String.format("%s:%s", url.getHost(), url.getPort());
        }

        return url.getHost();
    }
}

final class ContrastDependencyTreeVisitor implements DependencyNodeVisitor {
    @Getter
    final private HashMap<String, Map<String, DependencyDetails>> tree;

    final private Log log;
    private String parentArtifact;

    public ContrastDependencyTreeVisitor(Log log) {
        this.log = log;
        tree = new HashMap<String, Map<String,DependencyDetails>>(1);
    }

    private String formatArtifact(Artifact artifact, boolean parentFormat) {
        if (parentFormat) {
            return String.format("%s:%s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), artifact.getVersion());
        } else {
            return String.format("%s/%s@%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        }
    }

    public boolean visit(DependencyNode node) {
        Artifact artifact = node.getArtifact();
        if (node.getParent() == null) {
            parentArtifact = formatArtifact(artifact, true);
            log.debug("Visiting parent node " + parentArtifact);
            tree.put(parentArtifact, new HashMap<String, DependencyDetails>());
            return true;
        }

        String artifactKey = formatArtifact(artifact, false);
        log.debug("Visiting node " + artifactKey);

        DependencyDetails details = new DependencyDetails(artifact, (node.getParent().getParent() == null ? "direct" : "transitive"));
        for (DependencyNode child : node.getChildren()) {
            String descriptor = formatArtifact(child.getArtifact(), false);
            log.debug(">> Adding edge " + descriptor);
            details.addEdge(descriptor);
        }

        tree.get(parentArtifact).put(artifactKey, details);
        return true;
    }

    public boolean endVisit(DependencyNode node) {
        return true;
    }
}
