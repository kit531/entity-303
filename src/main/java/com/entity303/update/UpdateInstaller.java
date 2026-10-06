package com.entity303.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of the self-updater that do not need Minecraft or Fabric (so they can be tested on their own):
 * version comparison, checksums and swapping the mod's jar for the downloaded one.
 *
 * The running jar cannot be replaced while the game is up (Windows keeps it locked), so the swap happens when
 * the game exits: on Windows a small script waits for the jar to be released, on other systems the files are
 * simply moved by the shutdown hook.
 */
public final class UpdateInstaller {
	private static final Pattern SHA256 = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");
	private static final Pattern NUMBER = Pattern.compile("\\d+");

	private UpdateInstaller() {
	}

	/** True when `remote` is a higher version than `installed` ("v1.2.10" > "1.2.9"; anything after - or + is ignored). */
	public static boolean isNewer(String remote, String installed) {
		int[] a = parts(remote);
		int[] b = parts(installed);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y) {
				return x > y;
			}
		}
		return false;
	}

	private static int[] parts(String version) {
		String core = version.strip();
		if (core.startsWith("v") || core.startsWith("V")) {
			core = core.substring(1);
		}
		int cut = core.indexOf('-');
		if (cut >= 0) {
			core = core.substring(0, cut);
		}
		cut = core.indexOf('+');
		if (cut >= 0) {
			core = core.substring(0, cut);
		}
		Matcher m = NUMBER.matcher(core);
		int[] out = new int[0];
		while (m.find()) {
			out = java.util.Arrays.copyOf(out, out.length + 1);
			try {
				out[out.length - 1] = Integer.parseInt(m.group());
			} catch (NumberFormatException tooBig) {
				out[out.length - 1] = Integer.MAX_VALUE;
			}
		}
		return out;
	}

	/** The first 64-digit hex number in a ".sha256" file ("<hash>  <file name>" or just the hash), lower case; null if none. */
	public static String parseChecksum(String text) {
		Matcher m = SHA256.matcher(text);
		return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : null;
	}

	public static String sha256(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) > 0; ) {
				digest.update(buffer, 0, n);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	/**
	 * Replaces `installedJar` by `pendingJar`, which becomes `<same folder>/<newFileName>`. Call it as the game exits.
	 *
	 * @return a short description of what was arranged (for the log)
	 */
	public static String replaceOnExit(Path installedJar, Path pendingJar, String newFileName) throws IOException {
		Path destination = installedJar.resolveSibling(newFileName);
		if (!isWindows()) {
			// an open file can be moved and deleted here: do it right away
			Files.move(pendingJar, destination, StandardCopyOption.REPLACE_EXISTING);
			if (!destination.equals(installedJar)) {
				Files.deleteIfExists(installedJar);
			}
			return "moved " + pendingJar.getFileName() + " to " + destination;
		}
		Path script = pendingJar.resolveSibling("apply-update.cmd");
		Files.writeString(script, windowsScript(installedJar, pendingJar, destination), StandardCharsets.UTF_8);
		new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", "cmd.exe", "/c", script.toString())
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.start();
		return "started " + script + " (it swaps the jar as soon as the game has let go of it)";
	}

	/** The batch file: waits (up to ~3 minutes) until the old jar can be deleted, then moves the new one into place. */
	static String windowsScript(Path installedJar, Path pendingJar, Path destination) {
		String old = batch(installedJar);
		String fresh = batch(pendingJar);
		String dest = batch(destination);
		return String.join("\r\n",
			"@echo off",
			"set \"OLD=" + old + "\"",
			"set \"NEW=" + fresh + "\"",
			"set \"DEST=" + dest + "\"",
			"set /a tries=0",
			":wait",
			"set /a tries+=1",
			"del /F /Q \"%OLD%\" >nul 2>&1",
			"if not exist \"%OLD%\" goto swap",
			"if %tries% GEQ 90 goto fail",
			"ping -n 3 127.0.0.1 >nul",
			"goto wait",
			":swap",
			"move /Y \"%NEW%\" \"%DEST%\" >nul",
			"if errorlevel 1 goto fail",
			"(goto) 2>nul & del \"%~f0\"",
			":fail",
			"exit /b 1",
			"");
	}

	private static String batch(Path path) {
		return path.toAbsolutePath().toString().replace("%", "%%");
	}
}
