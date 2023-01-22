/**
 * Copyright (c) 2023 Eclipse contributors and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oomph.maven.generator;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class DependencyAnalyzer {

	public static void main(String[] args) throws Exception {

		var arguments = new ArrayList<>(Arrays.asList(args));

		var contentHandler = new ContentHandler(getArgument(arguments, "-cache"), getArgument(arguments, "-username"),
				getArgument(arguments, "-password"));

		var ignores = getArguments(arguments, "-ignore");
		var ignorePatterns = ignores.stream().filter(it -> !it.startsWith("/")).map(Pattern::compile)
				.collect(Collectors.toList());

		var analyzer = new Analyzer(contentHandler, ignorePatterns);

		var dependencies = new TreeSet<Dependency>();
		var reporter = new Reporter(getArgument(arguments, "-report"));
		var targets = getArguments(arguments, "-targets").stream().map(it -> it.split("="))
				.collect(Collectors.toMap(it -> it[0], it -> it[1]));
		for (var target : targets.entrySet()) {
			var uri = createURI(target.getValue());
			reporter.generateReport(contentHandler, analyzer, target.getKey(), uri);
			dependencies.addAll(analyzer.getTargetDependencies(uri));
		}

		var exclusions = getArguments(arguments, "-exclude");
		var exclusionPatterns = exclusions.stream().filter(it -> !it.startsWith("/")).map(Pattern::compile)
				.collect(Collectors.toList());
		dependencies.removeIf(it -> {
			return exclusionPatterns.stream().anyMatch(pattern -> it.matches(pattern));
		});

		// Remove any dependency for which there is a minor update version.
		dependencies.removeIf(it -> {
			return dependencies.stream().anyMatch(it2 -> {
				return it != it2 && it2.isSameArtfiact(it) && it2.version.compareTo(it.version.nextMajor()) < 0
						&& it.version.compareTo(it2.version) < 0;
			});
		});

		var dependencyUpdates = new TreeMap<Dependency, List<Version>>();
		for (var targetDependency : dependencies) {
			dependencyUpdates.put(targetDependency, List.of(targetDependency.version));
		}

		var merge = getArgument(arguments, "-merge");

		if (merge != null) {
			var mavenTarget = Path.of(merge);

			var mavenTargetContent = contentHandler.getContent(mavenTarget.toUri());

			var reducedMavenTarget = mavenTargetContent.replaceAll("(?s)(<dependencies>).*(\r?\n[\t ]+</dependencies>)",
					"$1$2");

			var allUpdateVersions = analyzer.getAllUpdateVersions(dependencies);
			allUpdateVersions.entrySet().removeIf(it -> {
				var nextMajor = it.getKey().version.nextMajor();
				var versions = it.getValue();
				versions.removeIf(version -> version.compareTo(nextMajor) >= 0);
				return versions.isEmpty();
			});
			dependencyUpdates.putAll(allUpdateVersions);

			var newMavenContent = replace(reducedMavenTarget, dependencyUpdates, true, true);
			Files.writeString(mavenTarget, newMavenContent);

			reporter.generateReport(contentHandler, analyzer, "merged-target", mavenTarget.toUri());
		}
	}

	private static String getArgument(List<String> arguments, String name) {
		var index = arguments.indexOf(name);
		if (index >= 0) {
			arguments.remove(index);
			if (index < arguments.size()) {
				return arguments.remove(index);
			}
		}

		return null;
	}

	private static List<String> getArguments(List<String> arguments, String name) {
		var result = new ArrayList<String>();
		var index = arguments.indexOf(name);
		if (index >= 0) {
			arguments.remove(index);
			while (index < arguments.size()) {
				String argument = arguments.get(index);
				if (argument.startsWith("-")) {
					break;
				}
				result.add(arguments.remove(index));
			}
		}

		return result;
	}

	private static final Pattern LOCAL_URI_PATTERN = Pattern.compile("local:(.*)");

	private static URI createURI(String uri) {
		Matcher matcher = LOCAL_URI_PATTERN.matcher(uri);
		return matcher.matches() ? Path.of(matcher.group(1)).toAbsolutePath().toUri() : URI.create(uri);
	}

	private static final Pattern INDENTATION_PATTERN = Pattern.compile(".*>\r?\n(\\s+)<locations>.*", Pattern.DOTALL);

	private static String replace(String content, Map<Dependency, List<Version>> dependencies, boolean ignoreMajor,
			boolean addMissing) {
		var indentation = INDENTATION_PATTERN.matcher(content).replaceAll("$1");
		for (var entry : dependencies.entrySet()) {
			var dependency = entry.getKey();
			var groupId = dependency.groupId;
			var artifactId = dependency.artifactId;
			var type = dependency.type;
			var actualVersion = dependency.version;

			var versions = entry.getValue();
			var version = versions.get(0);
			if (!ignoreMajor || version.compareTo(actualVersion.nextMajor()) < 0) {
				Pattern pattern = Pattern.compile("(<dependency>[^<]*" + //
						"<groupId>" + Pattern.quote(groupId) + "</groupId>[^<]*" + //
						"<artifactId>" + Pattern.quote(artifactId) + "</artifactId>[^<]*" + //
						"<version>)" + Pattern.quote(actualVersion.toString()) + "(</version>)", //
						Pattern.MULTILINE | Pattern.DOTALL);
				Matcher matcher = pattern.matcher(content);
				if (matcher.find()) {
					StringBuilder builder = new StringBuilder();
					matcher.appendReplacement(builder,
							Matcher.quoteReplacement(matcher.group(1) + version.toString() + matcher.group(2)));
					matcher.appendTail(builder);
					content = builder.toString();
				} else if (addMissing) {
					var matcher2 = Pattern.compile("(\r?\n)(\\s+)</dependencies>").matcher(content);
					if (!matcher2.find()) {
						throw new IllegalStateException("THe content must contain <dependencies>");
					}

					var linefeed = matcher2.group(1);
					var indent = matcher2.group(2);
					String separator = linefeed + indent + indentation;
					String dependencyElement = String.join(separator, List.of("<dependency>", //
							indentation + "<groupId>" + groupId + "</groupId>", //
							indentation + "<artifactId>" + artifactId + "</artifactId>", //
							indentation + "<version>" + version + "</version>", //
							indentation + "<type>" + (type == null ? "jar" : type) + "</type>", //
							"</dependency>"));

					StringBuilder builder = new StringBuilder();
					matcher2.appendReplacement(builder,
							Matcher.quoteReplacement(separator + dependencyElement + matcher2.group()));
					matcher2.appendTail(builder);
					content = builder.toString();
				}
			}
		}

		return content;
	}

	private static String getReportVersion(Dependency dependency, Version version) {
		return "[" + version + "](https://repo1.maven.org/maven2/" + dependency.groupId.replace('.', '/') + "/"
				+ dependency.artifactId + "/" + version + "/)";
	}

	private static String getText(Element element, String name) {
		var nodeList = element.getElementsByTagName(name);
		if (nodeList.getLength() > 0) {
			return nodeList.item(0).getTextContent();
		}
		return null;
	}

	private static <T extends Comparable<T>> int compare(T s1, T s2) {
		if (s1 == null) {
			if (s2 == null) {
				return 0;
			}
			return -1;
		}
		if (s2 == null) {
			return 1;
		}
		return s1.compareTo(s2);
	}

	static final XPathFactory XPATH_FACTORY = XPathFactory.newInstance();

	private static List<Element> evaluate(Document document, String expression) {
		XPath xPath = XPATH_FACTORY.newXPath();
		try {
			var nodeList = (NodeList) xPath.compile(expression).evaluate(document, XPathConstants.NODESET);
			var result = new ArrayList<Element>();
			for (int i = 0, length = nodeList.getLength(); i < length; ++i) {
				result.add((Element) nodeList.item(i));
			}
			return result;
		} catch (XPathExpressionException e) {
			throw new IllegalArgumentException(expression);
		}
	}

	private static class Reporter {

		private Path reportRoot;

		public Reporter(String report) {
			try {
				if (report != null) {
					this.reportRoot = Path.of(report);
					Files.createDirectories(this.reportRoot);
				} else {
				}
			} catch (IOException e) {
				throw new Error("Invalid report location:" + report);
			}
		}

		public void generateReport(ContentHandler contentHandler, Analyzer analyzer, String name, URI uri)
				throws IOException {
			if (reportRoot == null) {
				return;
			}

			var content = contentHandler.getContent(uri);

			var report = reportRoot.resolve(name);
			Files.createDirectories(report);

			Files.writeString(report.resolve("orignal.target"), content);

			var targetDependencies = analyzer.getTargetDependencies(uri);
			var targetDependencyVersions = analyzer.getAllUpdateVersions(targetDependencies);
			var newContent = replace(content, targetDependencyVersions, true, false);
			Files.writeString(report.resolve("updated.target"), newContent);

			try (var out = new PrintStream(Files.newOutputStream(report.resolve("REPORT.md")), false,
					StandardCharsets.UTF_8)) {

				out.print("# Target Platform: ");
				out.print(getMDLink(name, uri));
				out.println();

				for (var minor : new boolean[] { true, false }) {
					var groupId = "";
					var started = false;
					for (var entry : targetDependencyVersions.entrySet()) {
						var dependency = entry.getKey();
						var versions = new ArrayList<Version>(entry.getValue());
						var nextMajor = dependency.version.nextMajor();
						versions.removeIf(it -> minor ? it.compareTo(nextMajor) >= 0 : it.compareTo(nextMajor) < 0);
						if (!versions.isEmpty()) {
							if (!started) {
								out.println();
								out.print(minor ? "## Minor Updates" : "## Major Updates");
								out.println();
								started = true;
							}

							if (!dependency.groupId.equals(groupId)) {
								groupId = dependency.groupId;
								out.print(" - ");
								out.println(getMDLink(groupId, dependency.getGroupURI()));
							}

							out.print("    - ");
							out.print(getMDLink(dependency.artifactId, dependency.getArtifactFolderURI()));
							out.print(" **");
							out.print(getMDLink(dependency.version, dependency.getVersionFolderURI()));
							out.print("**");

							for (var version : versions) {
								out.print(" < ");
								out.print(getReportVersion(dependency, version));
							}
							out.println();
						}
					}
				}

				if (!newContent.equals(content)) {
					out.println();
					out.print("## Updates Applied");
					out.println();
					out.println(getMDLink("updated.target", "updated.target"));
				}

				out.println();
				out.print("## Content");
				out.println();

				var groupId = "";
				for (var dependency : targetDependencies) {
					if (!dependency.groupId.equals(groupId)) {
						groupId = dependency.groupId;
						out.print(" - ");
						out.println(getMDLink(groupId, dependency.getGroupURI()));
					}

					out.print("    - ");
					out.print(getMDLink(dependency.artifactId, dependency.getArtifactFolderURI()));
					out.print(" **");
					out.print(getMDLink(dependency.version, dependency.getVersionFolderURI()));
					out.print("**");
					out.println();
				}
			}
		}

		private static String getMDLink(Object label, Object uri) {
			return "[" + label + "](" + uri + ")";
		}

	}

	private static class Analyzer {
		private final ContentHandler contentHandler;

		private final List<Pattern> ignorePatterns;

		public Analyzer(ContentHandler contentHandler, List<Pattern> ignorePatterns) {
			this.contentHandler = contentHandler;
			this.ignorePatterns = ignorePatterns;
		}

		public List<Dependency> getTargetDependencies(URI location) throws IOException {
			var targetPlatform = contentHandler.getXMLContent(location);
			var mavenDependencies = evaluate(targetPlatform, "//dependency");
			var dependencies = new ArrayList<Dependency>();
			for (var mavenDependency : mavenDependencies) {
				var groupId = getText(mavenDependency, "groupId");
				var artifactId = getText(mavenDependency, "artifactId");
				var version = getText(mavenDependency, "version");
				var actualVersion = new Version(version);
				var type = getText(mavenDependency, "type");
				dependencies.add(new Dependency(groupId, artifactId, type == null ? "jar" : type, actualVersion));
			}

			Collections.sort(dependencies);
			return dependencies;
		}

		public Map<Dependency, List<Version>> getAllUpdateVersions(Collection<? extends Dependency> dependencies)
				throws IOException {
			var result = new TreeMap<Dependency, List<Version>>();
			for (var dependency : dependencies) {
				var versionList = getUpdateVersions(dependency);
				versionList.removeIf(it -> dependencies.contains(dependency.create(it)));
				if (!versionList.isEmpty()) {
					result.put(dependency, versionList);
				}
			}
			return result;
		}

		public List<Version> getUpdateVersions(Dependency dependency) throws IOException {

			var nextMajor = dependency.version.nextMajor();
			var nextAvailableVersion = dependency.version;
			var maxAvailableVersion = dependency.version;

			var availableVersions = getAvailableVersions(dependency);

			for (var availableVersion : availableVersions) {
				if (!ignorePatterns.stream().anyMatch(it -> dependency.create(availableVersion).matches(it))) {
					if (availableVersion.qualifier == null) {
						if (availableVersion.compareTo(nextMajor) < 0
								&& availableVersion.compareTo(nextAvailableVersion) > 0) {
							nextAvailableVersion = availableVersion;
						}

						if (availableVersion.compareTo(maxAvailableVersion) > 0) {
							maxAvailableVersion = availableVersion;
						}
					}
				}
			}

			List<Version> versionList = new ArrayList<>();
			if (!nextAvailableVersion.equals(dependency.version)) {
				if (!nextAvailableVersion.equals(maxAvailableVersion)) {
					versionList.add(nextAvailableVersion);
					versionList.add(maxAvailableVersion);
				} else {
					versionList.add(nextAvailableVersion);
				}
			} else if (!maxAvailableVersion.equals(dependency.version)) {
				versionList.add(maxAvailableVersion);
			}

			return versionList;
		}

		public List<Version> getAvailableVersions(Dependency dependency) throws IOException {
			var mavenMetadataXML = contentHandler.getXMLContent(dependency.getMavenMetadataXMLURI());
			var versions = evaluate(mavenMetadataXML, "/metadata/versioning/versions/version");
			return versions.stream().map(Element::getTextContent).filter(Version::isValid).map(Version::create)
					.collect(Collectors.toList());
		}
	}

	private static class Version implements Comparable<Version> {
		private static final Pattern VERSION_PATTERN = Pattern.compile("([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?(.+)?");

		private final int major;
		private final int minor;
		private final int micro;
		private final String qualifier;

		public static boolean isValid(String value) {
			return VERSION_PATTERN.matcher(value).matches();
		}

		public static Version create(String value) {
			return new Version(value);
		}

		public Version(String value) {
			Matcher matcher = VERSION_PATTERN.matcher(value);
			if (!matcher.matches()) {
				throw new IllegalArgumentException("Invalid version" + value);
			}

			major = Integer.parseInt(matcher.group(1));
			minor = Integer.parseInt(matcher.group(2));
			if (matcher.group(3) != null) {
				micro = Integer.parseInt(matcher.group(3));
			} else {
				micro = -1;
			}
			qualifier = matcher.group(4);
		}

		private Version(int major, int minor, int micro, String qualifier) {
			this.major = major;
			this.minor = minor;
			this.micro = micro;
			this.qualifier = qualifier;
		}

		public Version nextMajor() {
			return new Version(major + 1, 0, 0, null);
		}

		@Override
		public String toString() {
			return major + "." + minor + (micro != -1 ? "." + micro : "") + (qualifier == null ? "" : qualifier);
		}

		@Override
		public int compareTo(Version other) {
			int result = Integer.compare(major, other.major);
			if (result == 0) {
				result = Integer.compare(minor, other.minor);
			}
			if (result == 0) {
				result = Integer.compare(micro, other.micro);
			}
			if (result == 0) {
				result = compare(qualifier, other.qualifier);
			}
			return result;
		}

		@Override
		public int hashCode() {
			return Objects.hash(major, micro, minor, qualifier);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Version other = (Version) obj;
			return major == other.major && micro == other.micro && minor == other.minor
					&& Objects.equals(qualifier, other.qualifier);
		}
	}

	private static final class Dependency implements Comparable<Dependency> {
		private final String groupId;
		private final String artifactId;
		private final String type;
		private final Version version;

		public Dependency(String groupId, String artifactId, String type, Version version) {
			super();
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.type = type;
			this.version = version;
		}

		public URI getGroupURI() {
			return URI.create("https://repo1.maven.org/maven2/" + groupId.replace('.', '/') + "/");
		}

		public URI getArtifactFolderURI() {
			return URI.create(getGroupURI() + artifactId + "/");
		}

		public URI getMavenMetadataXMLURI() {
			return URI.create(getArtifactFolderURI() + "maven-metadata.xml");
		}

		public URI getVersionFolderURI() {
			return URI.create(getArtifactFolderURI() + version.toString());
		}

		public Dependency create(Version version) {
			if (this.version.equals(version)) {
				return this;
			}

			return new Dependency(groupId, artifactId, type, version);
		}

		public boolean isSameArtfiact(Dependency other) {
			return Objects.equals(artifactId, other.artifactId) && Objects.equals(groupId, other.groupId)
					&& Objects.equals(type, other.type);
		}

		@Override
		public int compareTo(Dependency other) {
			int result = compare(groupId, other.groupId);
			if (result == 0) {
				result = compare(artifactId, other.artifactId);
			}
			if (result == 0) {
				result = compare(type, other.type);
			}
			if (result == 0) {
				result = compare(version, other.version);
			}
			return result;
		}

		@Override
		public int hashCode() {
			return Objects.hash(artifactId, groupId, type, version);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Dependency other = (Dependency) obj;
			return Objects.equals(artifactId, other.artifactId) && Objects.equals(groupId, other.groupId)
					&& Objects.equals(type, other.type) && Objects.equals(version, other.version);
		}

		@Override
		public String toString() {
			return "" + groupId + ":" + artifactId + ":" + version + ":" + type;
		}

		public boolean matches(Pattern pattern) {
			return pattern.matcher("" + groupId + ":" + artifactId + ":" + version).matches();
		}
	}

	public static class ContentHandler {

		private static Map<URI, URI> REDIRECTIONS = new HashMap<>();

		private Path cache;

		private String username;

		private String password;

		public ContentHandler(String cache, String username, String password) {
			this.username = username;
			this.password = password;

			try {
				if (cache != null) {
					this.cache = Path.of(cache);
				} else {
					this.cache = Files.createTempDirectory("org.eclipse.maven.generator.cache");
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
				if (entry.getKey().toString().startsWith("org.eclipse.maven.generator.redirection")) {
					String[] components = entry.getValue().toString().split("->");
					REDIRECTIONS.put(createURI(components[0]), createURI(components[1]));
				}
			}
		}

		protected String basicGetContent(URI uri) throws IOException, InterruptedException {
			var httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
			var requestBuilder = HttpRequest.newBuilder(uri).GET();
			if (username != null && password != null) {
				requestBuilder = requestBuilder.header("Authorization", "Basic " + Base64.getEncoder()
						.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
			}
			var request = requestBuilder.build();
			var response = httpClient.send(request, BodyHandlers.ofString());
			var statusCode = response.statusCode();
			if (statusCode != 200) {
				throw new IOException("status code " + statusCode + " -> " + uri);
			}
			return response.body();
		}

		protected Path getCachePath(URI uri) {
			var decodedURI = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
			var uriSegments = decodedURI.split("[:/?#&;]+");
			var result = cache.resolve(String.join("/", uriSegments));
			return result;
		}

		public String getContent(URI uri) throws IOException {
			var redirectedURI = REDIRECTIONS.get(uri);
			if (redirectedURI != null) {
				return getContent(redirectedURI);
			}

			if ("file".equals(uri.getScheme())) {
				return Files.readString(Path.of(uri));
			}

			var path = getCachePath(uri);
			if (Files.isRegularFile(path)) {
				var lastModifiedTime = Files.getLastModifiedTime(path);
				var now = System.currentTimeMillis();
				var age = now - lastModifiedTime.toMillis();
				var ageInHours = age / 1000 / 60 / 60;
				if (ageInHours < 8) {
					return Files.readString(path);
				}
			}

			try {
				var content = basicGetContent(uri);
				Files.createDirectories(path.getParent());
				Files.writeString(path, content);
				return content;
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
		}

		public Document getXMLContent(URI uri) throws IOException {
			var content = getContent(uri);
			var factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setValidating(false);
			try {
				var builder = factory.newDocumentBuilder();
				return builder.parse(new InputSource(new StringReader(content)));
			} catch (ParserConfigurationException | SAXException e) {
				throw new IOException(e);
			}
		}
	}
}