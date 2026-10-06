package com.entity303.update;

import com.entity303.Entity303Mod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * Updates the mod itself from the GitHub Releases of its repository.
 *
 * On start-up (in the background, never blocking the game) it asks GitHub for the latest release. A newer
 * release is downloaded to config/entity303/pending/, checked against the release's .sha256 file and against
 * fabric.mod.json, and when the game exits the installed jar is swapped for it (see {@link UpdateInstaller}).
 * The new version is then active after the next start of the game.
 *
 * Turn it off with "autoUpdate": false in config/entity303.json. It does nothing in a development environment.
 */
public final class ModUpdater {
	/** The GitHub repository ("owner/name") whose Releases carry the mod. */
	public static final String DEFAULT_REPO = "OWNER/entity-303";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
	private static final Pattern JAR = Pattern.compile("entity-303-([0-9][0-9A-Za-z.+-]*)\\.jar");
	private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;

	private static volatile String readyVersion;
	private static volatile Consumer<String> readyListener;
	private static boolean hookInstalled;

	private ModUpdater() {
	}

	/** The config file: config/entity303.json. */
	public static final class Config {
		public boolean autoUpdate = true;
		public String repo = DEFAULT_REPO;
	}

	/** Version that is downloaded and waits for a restart of the game, or null. */
	public static String readyVersion() {
		return readyVersion;
	}

	/** Called (from a background thread) when an update has been downloaded; the client uses it to tell the player. */
	public static void setReadyListener(Consumer<String> listener) {
		readyListener = listener;
	}

	public static void start() {
		Thread thread = new Thread(ModUpdater::run, "entity303-updater");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run() {
		try {
			Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(Entity303Mod.MOD_ID);
			if (container.isEmpty() || FabricLoader.getInstance().isDevelopmentEnvironment()) {
				return;
			}
			Config config = loadConfig();
			if (!config.autoUpdate) {
				return;
			}
			Path installed = installedJar(container.get());
			if (installed == null) {
				Entity303Mod.LOGGER.info("[updater] the mod is not loaded from a single jar file: not updating");
				return;
			}
			String current = container.get().getMetadata().getVersion().getFriendlyString();
			Path pendingDir = FabricLoader.getInstance().getConfigDir().resolve(Entity303Mod.MOD_ID).resolve("pending");
			Files.createDirectories(pendingDir);

			// an update from a previous session that was never applied (the game crashed, for example)
			if (adoptPending(pendingDir, current, installed)) {
				return;
			}
			if (!REPO.matcher(config.repo).matches() || config.repo.startsWith("OWNER/")) {
				Entity303Mod.LOGGER.info("[updater] no GitHub repository configured (config/entity303.json): not checking for updates");
				return;
			}
			check(config.repo, current, pendingDir, installed);
		} catch (Exception e) {
			// never let the updater disturb the game; no network is the normal case
			Entity303Mod.LOGGER.warn("[updater] could not check for updates: {}", e.toString());
		}
	}

	private static void check(String repo, String current, Path pendingDir, Path installed) throws IOException, InterruptedException {
		HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		HttpRequest request = request("https://api.github.com/repos/" + repo + "/releases/latest")
			.header("Accept", "application/vnd.github+json")
			.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			Entity303Mod.LOGGER.info("[updater] GitHub answered {} (no release published yet?)", response.statusCode());
			return;
		}
		JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
		String tag = release.get("tag_name").getAsString();
		if (!UpdateInstaller.isNewer(tag, current)) {
			Entity303Mod.LOGGER.info("[updater] up to date ({})", current);
			return;
		}

		String jarName = null;
		String jarUrl = null;
		String sumUrl = null;
		JsonArray assets = release.getAsJsonArray("assets");
		for (JsonElement element : assets) {
			JsonObject asset = element.getAsJsonObject();
			String name = asset.get("name").getAsString();
			Matcher m = JAR.matcher(name);
			if (m.matches() && !name.endsWith("-sources.jar")) {
				jarName = name;
				jarUrl = asset.get("browser_download_url").getAsString();
			}
		}
		if (jarName == null) {
			Entity303Mod.LOGGER.warn("[updater] release {} has no mod jar", tag);
			return;
		}
		for (JsonElement element : assets) {
			JsonObject asset = element.getAsJsonObject();
			if (asset.get("name").getAsString().equals(jarName + ".sha256")) {
				sumUrl = asset.get("browser_download_url").getAsString();
			}
		}
		if (sumUrl == null || !trusted(jarUrl) || !trusted(sumUrl)) {
			Entity303Mod.LOGGER.warn("[updater] release {} has no checksum file (or an unexpected download address): not installing it", tag);
			return;
		}

		String expected = UpdateInstaller.parseChecksum(http.send(request(sumUrl).build(), HttpResponse.BodyHandlers.ofString()).body());
		if (expected == null) {
			Entity303Mod.LOGGER.warn("[updater] the checksum file of {} is not readable: not installing it", tag);
			return;
		}

		Path part = pendingDir.resolve(jarName + ".part");
		MessageDigest digest = sha256Digest();
		HttpResponse<InputStream> download = http.send(request(jarUrl).build(), HttpResponse.BodyHandlers.ofInputStream());
		if (download.statusCode() != 200) {
			Entity303Mod.LOGGER.warn("[updater] download answered {}", download.statusCode());
			return;
		}
		long total = 0;
		try (InputStream in = download.body(); OutputStream out = Files.newOutputStream(part)) {
			byte[] buffer = new byte[16384];
			for (int n; (n = in.read(buffer)) > 0; ) {
				total += n;
				if (total > MAX_JAR_BYTES) {
					throw new IOException("the download is larger than " + MAX_JAR_BYTES + " bytes");
				}
				digest.update(buffer, 0, n);
				out.write(buffer, 0, n);
			}
		} catch (IOException e) {
			Files.deleteIfExists(part);
			throw e;
		}
		if (!HexFormat.of().formatHex(digest.digest()).equals(expected) || !looksLikeThisMod(part)) {
			Files.deleteIfExists(part);
			Entity303Mod.LOGGER.warn("[updater] {} failed the integrity check: deleted, not installing it", jarName);
			return;
		}
		Path ready = pendingDir.resolve(jarName);
		Files.move(part, ready, StandardCopyOption.REPLACE_EXISTING);
		Entity303Mod.LOGGER.info("[updater] version {} downloaded; it is installed when the game closes", tag);
		markReady(tag, ready, installed, jarName);
	}

	/** Update already sitting in pending/ from an earlier session: install it (and clean up older leftovers). */
	private static boolean adoptPending(Path pendingDir, String current, Path installed) throws IOException {
		String bestVersion = null;
		Path best = null;
		try (var files = Files.list(pendingDir)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				Matcher m = JAR.matcher(file.getFileName().toString());
				if (!m.matches()) {
					continue;
				}
				String version = m.group(1);
				if (UpdateInstaller.isNewer(version, current) && (bestVersion == null || UpdateInstaller.isNewer(version, bestVersion))
					&& looksLikeThisMod(file)) {
					if (best != null) {
						Files.deleteIfExists(best);
					}
					bestVersion = version;
					best = file;
				} else {
					Files.deleteIfExists(file);
				}
			}
		}
		if (best == null) {
			return false;
		}
		Entity303Mod.LOGGER.info("[updater] version {} was downloaded earlier and is installed when the game closes", bestVersion);
		markReady(bestVersion, best, installed, best.getFileName().toString());
		return true;
	}

	private static synchronized void markReady(String version, Path pending, Path installed, String newFileName) {
		readyVersion = version;
		if (!hookInstalled) {
			hookInstalled = true;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					System.out.println("[entity303 updater] " + UpdateInstaller.replaceOnExit(installed, pending, newFileName));
				} catch (Exception e) {
					System.err.println("[entity303 updater] could not install the update: " + e);
				}
			}, "entity303-updater-install"));
		}
		Consumer<String> listener = readyListener;
		if (listener != null) {
			listener.accept(version);
		}
	}

	// ----------------------------------------------------------------- helpers ---
	private static Config loadConfig() throws IOException {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(Entity303Mod.MOD_ID + ".json");
		if (Files.exists(file)) {
			try {
				Config config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
				if (config != null) {
					if (config.repo == null) {
						config.repo = DEFAULT_REPO;
					}
					return config;
				}
			} catch (RuntimeException broken) {
				Entity303Mod.LOGGER.warn("[updater] config/entity303.json is not valid JSON: using the defaults");
			}
			return new Config();
		}
		Config config = new Config();
		Files.createDirectories(file.getParent());
		Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
		return config;
	}

	/** The jar the mod is loaded from, or null (development environment, unpacked folder, ...). */
	private static Path installedJar(ModContainer container) {
		ModOrigin origin = container.getOrigin();
		if (origin.getKind() != ModOrigin.Kind.PATH) {
			return null;
		}
		List<Path> paths = origin.getPaths();
		if (paths.size() != 1) {
			return null;
		}
		Path path = paths.get(0);
		return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path : null;
	}

	private static HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(60))
			.header("User-Agent", "entity303-updater");
	}

	/** Only GitHub itself is allowed to serve the files. */
	private static boolean trusted(String url) {
		return url.startsWith("https://github.com/");
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** The downloaded file must be a jar whose fabric.mod.json says it is this mod. */
	private static boolean looksLikeThisMod(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return false;
			}
			try (InputStream in = zip.getInputStream(entry)) {
				JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
				return Entity303Mod.MOD_ID.equals(json.get("id").getAsString());
			}
		} catch (Exception notAJar) {
			return false;
		}
	}
}
