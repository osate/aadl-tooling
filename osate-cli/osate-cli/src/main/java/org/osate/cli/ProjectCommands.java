/*******************************************************************************
 * OSATE Command Line Interface
 *
 * Copyright 2026 Carnegie Mellon University.
 *
 * NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
 * FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
 * EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
 * FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
 * MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
 * FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 *
 * Licensed under a BSD (SEI)-style license, please see LICENSE.txt
 * or contact permission@sei.cmu.edu for full terms.
 *
 * [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
 * distribution.  Please see Copyright notice for non-US Government use and distribution.
 *
 * This Software includes and/or makes use of Third-Party Software each subject to its own license.
 *
 * DM26-0838
 ******************************************************************************/
package org.osate.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Local commands for managing AADL projects under the current working directory. */
final class ProjectCommands {

	private static final String PROJECT_FILE = ".project";
	private static final String XTEXT_BUILDER = "org.eclipse.xtext.ui.shared.xtextBuilder";
	private static final String AADL_NATURE = "org.osate.core.aadlnature";
	private static final String XTEXT_NATURE = "org.eclipse.xtext.ui.shared.xtextNature";
	private static final Pattern PROJECTS_ELEMENT = Pattern.compile(
			"(?s)(<projects(?:\\s[^>]*)?>)(.*?)(</projects>)");
	private static final Pattern PROJECT_ENTRY = Pattern.compile("(?:\\r?\\n)([ \\t]*)<project(?:\\s|>)");

	private ProjectCommands() {
	}

	static int run(List<String> args) throws IOException {
		return run(Path.of("").toAbsolutePath().normalize(), args, System.out, System.err);
	}

	static int run(Path workspace, List<String> args, PrintStream out, PrintStream err) throws IOException {
		try {
			return switch (args.get(0)) {
				case "list" -> list(workspace, out);
				case "create" -> create(workspace, args, out);
				case "show" -> show(workspace, args.get(1), out);
				case "add-dependency" -> addDependencies(workspace, args, out);
				case "remove-dependency" -> removeDependencies(workspace, args, out);
				case "validate" -> validate(workspace, out, err);
				default -> throw new ProjectException("unknown project command: " + args.get(0));
			};
		} catch (ProjectException e) {
			err.println("ERR " + e.getMessage());
			return 1;
		}
	}

	private static int list(Path workspace, PrintStream out) throws IOException, ProjectException {
		for (var project : discover(workspace)) {
			out.print(project.name());
			if (!project.dependencies().isEmpty()) {
				out.print(" -> ");
				out.print(String.join(", ", project.dependencies()));
			}
			out.println();
		}
		return 0;
	}

	private static int show(Path workspace, String name, PrintStream out) throws IOException, ProjectException {
		var project = findProject(discover(workspace), name);
		out.println("Name: " + project.name());
		out.println("Directory: " + project.directory());
		out.println("Dependencies:");
		for (var dependency : project.dependencies()) {
			out.println("  " + dependency);
		}
		return 0;
	}

	private static int create(Path workspace, List<String> args, PrintStream out)
			throws IOException, ProjectException {
		var options = parseCreateOptions(args);
		validateDirectoryName(options.name());
		var projects = discover(workspace);
		if (projects.stream().anyMatch(project -> project.name().equals(options.name()))) {
			throw new ProjectException("project already exists: " + options.name());
		}
		for (var dependency : options.dependencies()) {
			if (options.name().equals(dependency)) {
				throw new ProjectException("project cannot depend on itself: " + dependency);
			}
			findProject(projects, dependency);
		}

		var directory = workspace.resolve(options.name()).normalize();
		var projectFile = directory.resolve(PROJECT_FILE);
		boolean createdDirectory = false;
		if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
				throw new ProjectException("project path is not a directory: " + directory);
			}
			if (Files.exists(projectFile, LinkOption.NOFOLLOW_LINKS)) {
				throw new ProjectException("project already exists: " + options.name());
			}
			if (!options.adopt()) {
				throw new ProjectException("directory already exists; use --adopt to add .project: " + directory);
			}
		} else {
			Files.createDirectory(directory);
			createdDirectory = true;
		}

		try {
			writeAtomically(projectFile, newProjectXml(options.name(), options.dependencies()));
		} catch (IOException e) {
			if (createdDirectory) {
				Files.deleteIfExists(directory);
			}
			throw e;
		}
		out.println("Created " + projectFile);
		return 0;
	}

	private static CreateOptions parseCreateOptions(List<String> args) {
		var dependencies = new LinkedHashSet<String>();
		boolean adopt = false;
		for (int i = 2; i < args.size(); i++) {
			if ("--adopt".equals(args.get(i))) {
				adopt = true;
			} else {
				dependencies.add(args.get(++i));
			}
		}
		return new CreateOptions(args.get(1), List.copyOf(dependencies), adopt);
	}

	private static int addDependencies(Path workspace, List<String> args, PrintStream out)
			throws IOException, ProjectException {
		var projects = discover(workspace);
		var project = findProject(projects, args.get(1));
		var dependencies = new LinkedHashSet<>(project.dependencies());
		boolean changed = false;
		for (int i = 2; i < args.size(); i++) {
			var dependency = args.get(i);
			if (project.name().equals(dependency)) {
				throw new ProjectException("project cannot depend on itself: " + dependency);
			}
			findProject(projects, dependency);
			changed |= dependencies.add(dependency);
		}
		if (changed) {
			writeDependencies(project.file(), List.copyOf(dependencies));
			out.println("Updated dependencies for " + project.name());
		} else {
			out.println("Dependencies unchanged for " + project.name());
		}
		return 0;
	}

	private static int removeDependencies(Path workspace, List<String> args, PrintStream out)
			throws IOException, ProjectException {
		var project = findProject(discover(workspace), args.get(1));
		var removals = new HashSet<>(args.subList(2, args.size()));
		var dependencies = new ArrayList<>(project.dependencies());
		var changed = dependencies.removeIf(removals::contains);
		if (changed) {
			writeDependencies(project.file(), dependencies);
			out.println("Updated dependencies for " + project.name());
		} else {
			out.println("Dependencies unchanged for " + project.name());
		}
		return 0;
	}

	private static int validate(Path workspace, PrintStream out, PrintStream err) throws IOException {
		var issues = new ArrayList<Issue>();
		var projects = discoverForValidation(workspace, issues);
		if (projects.isEmpty()) {
			issues.add(Issue.error(workspace.toString(), "no AADL projects found"));
		}

		var byName = new HashMap<String, List<Project>>();
		for (var project : projects) {
			byName.computeIfAbsent(project.name(), ignored -> new ArrayList<>()).add(project);
			var directoryName = project.directory().getFileName().toString();
			if (!directoryName.equals(project.name())) {
				issues.add(Issue.warning(project.name(), "directory name '" + directoryName
						+ "' differs from declared project name"));
			}
			validateConfiguration(project, issues);
			var seenDependencies = new HashSet<String>();
			for (var dependency : project.dependencies()) {
				if (!seenDependencies.add(dependency)) {
					issues.add(Issue.error(project.name(), "duplicate dependency: " + dependency));
				}
				if (project.name().equals(dependency)) {
					issues.add(Issue.error(project.name(), "project depends on itself"));
				}
			}
		}

		for (var entry : byName.entrySet()) {
			if (entry.getValue().size() > 1) {
				for (var project : entry.getValue()) {
					issues.add(Issue.error(project.directory().toString(),
							"duplicate project name: " + entry.getKey()));
				}
			}
		}
		for (var project : projects) {
			for (var dependency : project.dependencies()) {
				if (!byName.containsKey(dependency)) {
					issues.add(Issue.error(project.name(), "dependency not found: " + dependency));
				}
			}
		}
		findCycles(projects, byName, issues);

		issues.sort(Comparator.comparing(Issue::severity).thenComparing(Issue::subject)
				.thenComparing(Issue::message));
		int errors = 0;
		int warnings = 0;
		for (var issue : issues) {
			err.println(issue.severity() + " " + issue.subject() + ": " + issue.message());
			if ("ERROR".equals(issue.severity())) {
				errors++;
			} else {
				warnings++;
			}
		}
		if (errors == 0) {
			out.println("Valid workspace: " + projects.size() + " project(s), " + warnings + " warning(s)");
			return 0;
		}
		err.println("Found " + errors + " error(s), " + warnings + " warning(s)");
		return 1;
	}

	private static List<Project> discoverForValidation(Path workspace, List<Issue> issues) throws IOException {
		var projects = new ArrayList<Project>();
		for (var directory : projectDirectories(workspace)) {
			try {
				projects.add(readProject(directory));
			} catch (ProjectException e) {
				issues.add(Issue.error(directory.resolve(PROJECT_FILE).toString(), e.getMessage()));
			}
		}
		projects.sort(Comparator.comparing(Project::name).thenComparing(p -> p.directory().toString()));
		return projects;
	}

	private static List<Project> discover(Path workspace) throws IOException, ProjectException {
		var projects = new ArrayList<Project>();
		for (var directory : projectDirectories(workspace)) {
			projects.add(readProject(directory));
		}
		projects.sort(Comparator.comparing(Project::name).thenComparing(p -> p.directory().toString()));
		return projects;
	}

	private static List<Path> projectDirectories(Path workspace) throws IOException {
		if (!Files.isDirectory(workspace)) {
			throw new IOException("workspace is not a directory: " + workspace);
		}
		try (var children = Files.list(workspace)) {
			return children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> Files.isRegularFile(path.resolve(PROJECT_FILE), LinkOption.NOFOLLOW_LINKS))
					.sorted()
					.toList();
		}
	}

	private static Project findProject(List<Project> projects, String name) throws ProjectException {
		var matches = projects.stream().filter(project -> project.name().equals(name)).toList();
		if (matches.isEmpty()) {
			throw new ProjectException("project not found in current workspace: " + name);
		}
		if (matches.size() > 1) {
			throw new ProjectException("project name is not unique in current workspace: " + name);
		}
		return matches.get(0);
	}

	private static Project readProject(Path directory) throws ProjectException {
		var file = directory.resolve(PROJECT_FILE);
		var document = parseDocument(file);
		var root = document.getDocumentElement();
		if (root == null || !"projectDescription".equals(root.getTagName())) {
			throw new ProjectException("root element must be <projectDescription>");
		}
		var name = directChildText(root, "name");
		if (name == null || name.isBlank()) {
			throw new ProjectException("missing project name");
		}
		var dependencies = new ArrayList<String>();
		var projects = directChild(root, "projects");
		if (projects != null) {
			for (var child = projects.getFirstChild(); child != null; child = child.getNextSibling()) {
				if (child instanceof Element element && "project".equals(element.getTagName())) {
					var dependency = element.getTextContent().trim();
					if (!dependency.isEmpty()) {
						dependencies.add(dependency);
					}
				}
			}
		}
		return new Project(directory.toAbsolutePath().normalize(), file, name.trim(), List.copyOf(dependencies), document);
	}

	private static Document parseDocument(Path file) throws ProjectException {
		try {
			var factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			var builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new ErrorHandler() {
				@Override
				public void warning(SAXParseException exception) throws SAXException {
					throw exception;
				}

				@Override
				public void error(SAXParseException exception) throws SAXException {
					throw exception;
				}

				@Override
				public void fatalError(SAXParseException exception) throws SAXException {
					throw exception;
				}
			});
			return builder.parse(file.toFile());
		} catch (ParserConfigurationException | SAXException | IOException e) {
			throw new ProjectException("cannot read .project: " + e.getMessage());
		}
	}

	private static void validateConfiguration(Project project, List<Issue> issues) {
		if (directChild(project.document().getDocumentElement(), "projects") == null) {
			issues.add(Issue.error(project.name(), "missing <projects> dependency section"));
		}
		var builders = descendantNames(project.document(), "buildCommand");
		if (!builders.contains(XTEXT_BUILDER)) {
			issues.add(Issue.error(project.name(), "missing Xtext builder: " + XTEXT_BUILDER));
		}
		var natures = descendantTexts(project.document(), "nature");
		if (!natures.contains(AADL_NATURE)) {
			issues.add(Issue.error(project.name(), "missing AADL nature: " + AADL_NATURE));
		}
		if (!natures.contains(XTEXT_NATURE)) {
			issues.add(Issue.error(project.name(), "missing Xtext nature: " + XTEXT_NATURE));
		}
	}

	private static Set<String> descendantNames(Document document, String parentTag) {
		var names = new HashSet<String>();
		var parents = document.getElementsByTagName(parentTag);
		for (int i = 0; i < parents.getLength(); i++) {
			if (parents.item(i) instanceof Element parent) {
				var name = directChildText(parent, "name");
				if (name != null) {
					names.add(name.trim());
				}
			}
		}
		return names;
	}

	private static Set<String> descendantTexts(Document document, String tag) {
		var values = new HashSet<String>();
		var nodes = document.getElementsByTagName(tag);
		for (int i = 0; i < nodes.getLength(); i++) {
			values.add(nodes.item(i).getTextContent().trim());
		}
		return values;
	}

	private static void findCycles(List<Project> projects, Map<String, List<Project>> byName, List<Issue> issues) {
		var state = new HashMap<String, Integer>();
		var path = new ArrayDeque<String>();
		var reported = new HashSet<String>();
		for (var project : projects) {
			if (byName.getOrDefault(project.name(), List.of()).size() == 1) {
				findCycles(project.name(), byName, state, path, reported, issues);
			}
		}
	}

	private static void findCycles(String name, Map<String, List<Project>> byName, Map<String, Integer> state,
			ArrayDeque<String> path, Set<String> reported, List<Issue> issues) {
		if (state.getOrDefault(name, 0) == 2) {
			return;
		}
		state.put(name, 1);
		path.addLast(name);
		var projects = byName.get(name);
		if (projects != null && projects.size() == 1) {
			for (var dependency : projects.get(0).dependencies()) {
				if (!byName.containsKey(dependency) || byName.get(dependency).size() != 1) {
					continue;
				}
				if (state.getOrDefault(dependency, 0) == 1) {
					var cycle = new ArrayList<String>();
					boolean inCycle = false;
					for (var item : path) {
						if (item.equals(dependency)) {
							inCycle = true;
						}
						if (inCycle) {
							cycle.add(item);
						}
					}
					cycle.add(dependency);
					var message = String.join(" -> ", cycle);
					if (reported.add(message)) {
						issues.add(Issue.error(name, "dependency cycle: " + message));
					}
				} else {
					findCycles(dependency, byName, state, path, reported, issues);
				}
			}
		}
		path.removeLast();
		state.put(name, 2);
	}

	private static void writeDependencies(Path file, List<String> dependencies) throws ProjectException, IOException {
		var document = parseDocument(file);
		var root = document.getDocumentElement();
		var projects = directChild(root, "projects");
		if (projects == null) {
			throw new ProjectException("missing <projects> dependency section");
		}

		var source = Files.readString(file, StandardCharsets.UTF_8);
		var matcher = PROJECTS_ELEMENT.matcher(source);
		if (!matcher.find()) {
			throw new ProjectException("cannot locate <projects> dependency section");
		}
		var lineSeparator = source.contains("\r\n") ? "\r\n" : "\n";
		int lineStart = source.lastIndexOf('\n', matcher.start(1));
		lineStart = lineStart < 0 ? 0 : lineStart + 1;
		var openingIndent = source.substring(lineStart, matcher.start(1));
		if (!openingIndent.isBlank()) {
			openingIndent = "\t";
		}
		var entryMatcher = PROJECT_ENTRY.matcher(matcher.group(2));
		var entryIndent = entryMatcher.find() ? entryMatcher.group(1) : openingIndent + "\t";

		var body = new StringBuilder();
		for (var dependency : dependencies) {
			body.append(lineSeparator).append(entryIndent).append("<project>")
					.append(escapeXml(dependency)).append("</project>");
		}
		body.append(lineSeparator).append(openingIndent);
		var updated = source.substring(0, matcher.start(2)) + body + source.substring(matcher.end(2));
		writeAtomically(file, updated);
	}

	private static void writeAtomically(Path file, String content) throws IOException {
		var temporary = Files.createTempFile(file.getParent(), ".project.", ".tmp");
		try {
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			moveIntoPlace(temporary, file);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void moveIntoPlace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String newProjectXml(String name, List<String> dependencies) {
		var projects = new StringBuilder();
		for (var dependency : dependencies) {
			projects.append("\t\t<project>").append(escapeXml(dependency)).append("</project>\n");
		}
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
					<name>%s</name>
					<comment></comment>
					<projects>
				%s	</projects>
					<buildSpec>
						<buildCommand>
							<name>org.eclipse.xtext.ui.shared.xtextBuilder</name>
							<arguments>
							</arguments>
						</buildCommand>
					</buildSpec>
					<natures>
						<nature>org.osate.core.aadlnature</nature>
						<nature>org.eclipse.xtext.ui.shared.xtextNature</nature>
					</natures>
				</projectDescription>
				""".formatted(escapeXml(name), projects);
	}

	private static String escapeXml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&apos;");
	}

	private static void validateDirectoryName(String name) throws ProjectException {
		boolean invalid = name.isBlank() || ".".equals(name) || "..".equals(name) || name.contains("/")
				|| name.contains("\\") || name.chars().anyMatch(Character::isISOControl);
		try {
			invalid |= Path.of(name).isAbsolute();
		} catch (RuntimeException e) {
			invalid = true;
		}
		if (invalid) {
			throw new ProjectException("invalid project name: " + name);
		}
	}

	private static Element directChild(Element parent, String tag) {
		for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Element element && tag.equals(element.getTagName())) {
				return element;
			}
		}
		return null;
	}

	private static String directChildText(Element parent, String tag) {
		var child = directChild(parent, tag);
		return child == null ? null : child.getTextContent();
	}

	private record CreateOptions(String name, List<String> dependencies, boolean adopt) {
	}

	private record Project(Path directory, Path file, String name, List<String> dependencies, Document document) {
	}

	private record Issue(String severity, String subject, String message) {
		static Issue error(String subject, String message) {
			return new Issue("ERROR", subject, message);
		}

		static Issue warning(String subject, String message) {
			return new Issue("WARNING", subject, message);
		}
	}

	private static final class ProjectException extends Exception {
		private static final long serialVersionUID = 1L;

		ProjectException(String message) {
			super(message);
		}
	}
}
