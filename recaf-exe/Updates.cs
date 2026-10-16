using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

namespace LightningRecaf.Updates
{
    // Shared by the standalone bootstrap and the application entry point.
    internal static class AppUpdates
    {
        internal const string LatestUrl = "https://api.github.com/repos/sub5larp/LightningRecaf/releases/latest";
        const string ReleasePrefix = "https://github.com/sub5larp/LightningRecaf/releases/download/";
        const long MaxAssetSize = 512L * 1024 * 1024;
        const string DataVariable = "LIGHTNINGRECAF_DATA";
        const string EntryVariable = "LIGHTNINGRECAF_ENTRY";

        internal sealed class Release
        {
            public Version Version;
            public string Hash;
            public string Url;
            public long Size;
            public string Key { get { return "v" + Version.ToString(3) + "-" + Hash.Substring(0, 16); } }
        }

        internal static Version CurrentVersion {
            get { var v = Assembly.GetExecutingAssembly().GetName().Version; return new Version(v.Major, v.Minor, v.Build); }
        }
        internal static string DataDirectory(string fallback) {
            string value = Environment.GetEnvironmentVariable(DataVariable);
            return Path.GetFullPath(string.IsNullOrWhiteSpace(value) ? fallback : value);
        }
        internal static string EntryExecutable(string fallback) {
            string value = Environment.GetEnvironmentVariable(EntryVariable);
            return string.IsNullOrWhiteSpace(value) ? fallback : Path.GetFullPath(value);
        }
        internal static bool HasDataOverride { get { return !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(DataVariable)); } }
        internal static string Root(string data) { return Path.Combine(data, "updates"); }
        internal static bool Enabled(string data, string[] args) {
            foreach (string arg in args) {
                if (arg == "--") break;
                if (new[] { "--no-update", "--install", "--uninstall", "--extract" }.Contains(arg, StringComparer.OrdinalIgnoreCase)) return false;
            }
            try {
                string path = Path.Combine(data, "launcher", "recaf-exe.ini");
                if (File.Exists(path)) foreach (string raw in File.ReadAllLines(path)) {
                    string line = raw.Trim();
                    if (line.StartsWith("#") || line.StartsWith(";")) continue;
                    string[] pair = line.Split(new[] { '=' }, 2);
                    if (pair.Length == 2 && pair[0].Trim().Equals("auto-update", StringComparison.OrdinalIgnoreCase))
                        return new[] { "true", "yes", "1", "" }.Contains(pair[1].Trim(), StringComparer.OrdinalIgnoreCase);
                }
            } catch (IOException) { } catch (UnauthorizedAccessException) { }
            return true;
        }
        static object Parse(string json) { return new JavaScriptSerializer { MaxJsonLength = 2 * 1024 * 1024, RecursionLimit = 32 }.DeserializeObject(json); }
        static object Get(object obj, string key) {
            var map = obj as IDictionary<string, object>; object value;
            return map != null && map.TryGetValue(key, out value) ? value : null;
        }
        static string Str(object obj, string key) { return Get(obj, key) as string; }
        static long Number(object obj, string key) {
            object value = Get(obj, key);
            if (!(value is int) && !(value is long)) return -1;
            return Convert.ToInt64(value);
        }
        internal static Version ParseVersion(string text) {
            Version version;
            if (text == null || !Regex.IsMatch(text, @"\Av?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\z")
                || !Version.TryParse(text.TrimStart('v'), out version)) return null;
            return version;
        }
        internal static Release ReadRelease(string json, Version current) {
            object root = Parse(json);
            if (!object.Equals(Get(root, "draft"), false) || !object.Equals(Get(root, "prerelease"), false)) return null;
            string tag = Str(root, "tag_name");
            Version version = ParseVersion(tag);
            if (version == null || version <= current) return null;
            var assets = Get(root, "assets") as object[];
            if (assets == null) return null;
            var matches = assets.Where(a => Str(a, "name") == "LightningRecaf.exe").ToArray();
            if (matches.Length != 1) return null;
            object asset = matches[0];
            string digest = Str(asset, "digest");
            long size = Number(asset, "size");
            string url = Str(asset, "browser_download_url");
            if (Str(asset, "state") != "uploaded" || size <= 0 || size > MaxAssetSize
                || digest == null || !Regex.IsMatch(digest, @"\Asha256:[0-9a-fA-F]{64}\z")
                || url != ReleasePrefix + tag + "/LightningRecaf.exe") return null;
            return new Release { Version = version, Hash = digest.Substring(7).ToLowerInvariant(), Size = size, Url = url };
        }
        internal static string HashFile(string path) {
            using (var input = File.OpenRead(path)) using (var hash = SHA256.Create())
                return BitConverter.ToString(hash.ComputeHash(input)).Replace("-", "").ToLowerInvariant();
        }
        internal static bool ValidExecutable(string path, Release release) {
            if (!File.Exists(path) || new FileInfo(path).Length != release.Size || HashFile(path) != release.Hash) return false;
            Version version = AssemblyName.GetAssemblyName(path).Version;
            return version.Major == release.Version.Major && version.Minor == release.Version.Minor
                && version.Build == release.Version.Build && version.Revision == 0;
        }
        static string Executable(string root, Release release) { return Path.Combine(root, release.Key, "LightningRecaf.exe"); }
        internal static string FindReady(string data, Version current) {
            string root = Root(data), marker = Path.Combine(root, "ready.json");
            if (!File.Exists(marker) || new FileInfo(marker).Length > 4096) return null;
            object state = Parse(File.ReadAllText(marker));
            Version version = ParseVersion(Str(state, "version"));
            string hash = Str(state, "sha256");
            long size = Number(state, "size");
            if (version == null || version <= current || hash == null || !Regex.IsMatch(hash, @"\A[0-9a-f]{64}\z")
                || size <= 0 || size > MaxAssetSize || File.Exists(Path.Combine(root, "failed-" + hash + ".txt"))) return null;
            var release = new Release { Version = version, Hash = hash, Size = size };
            string exe = Executable(root, release);
            return ValidExecutable(exe, release) ? exe : null;
        }
        internal static string Quote(string value) {
            var result = new StringBuilder("\""); int slashes = 0;
            foreach (char c in value) {
                if (c == '\\') { slashes++; continue; }
                result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes).Append(c); slashes = 0;
            }
            return result.Append('\\', slashes * 2).Append('"').ToString();
        }
        internal static ProcessStartInfo StartInfo(string exe, string data, string entry, string[] args) {
            var info = new ProcessStartInfo(exe, string.Join(" ", args.Select(Quote))) {
                UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Environment.CurrentDirectory
            };
            info.EnvironmentVariables[DataVariable] = Path.GetFullPath(data);
            info.EnvironmentVariables[EntryVariable] = Path.GetFullPath(entry);
            return info;
        }
        internal static bool TryLaunchReady(string data, Version current, string entry, string[] args) {
            if (!Enabled(data, args)) return false;
            try {
                string exe = FindReady(data, current);
                if (exe == null) return false;
                using (var child = Process.Start(StartInfo(exe, data, entry, args))) {
                    child.WaitForExit();
                    if (child.ExitCode == 0) return true;
                }
                File.WriteAllText(Path.Combine(Root(data), "failed-" + HashFile(exe) + ".txt"), "Startup failed; retained the previous version.");
                WriteLog(data, "Updated application could not start; using the previous version.");
            } catch (Exception error) { WriteLog(data, "Update activation skipped: " + error.Message); }
            return false;
        }
        internal static void StartBackgroundCheck(string data, string entry, string[] args) {
            if (!Enabled(data, args)) return;
            try {
                using (Process.Start(StartInfo(Assembly.GetExecutingAssembly().Location, data, entry, new[] { "--update-check" }))) { }
            } catch (Exception error) { WriteLog(data, "Update check could not start: " + error.Message); }
        }
        internal static void Check(string data, Version current) {
            Check(data, current, FetchLatest, Download);
        }
        internal static void Check(string data, Version current, Func<string> fetch, Action<Release, string> download) {
            if (!Enabled(data, new string[0])) return;
            try {
                string root = Root(data);
                Directory.CreateDirectory(root);
                using (var gate = new FileStream(Path.Combine(root, "check.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) {
                    string stamp = Path.Combine(root, "last-check.txt");
                    var age = DateTime.UtcNow - File.GetLastWriteTimeUtc(stamp);
                    if (File.Exists(stamp) && age >= TimeSpan.Zero && age < TimeSpan.FromMinutes(15)) return;
                    File.WriteAllText(stamp, DateTime.UtcNow.ToString("o"));
                    Release release = ReadRelease(fetch(), current);
                    if (release == null) { WriteLog(data, "No newer complete stable release."); return; }
                    if (File.Exists(Path.Combine(root, "failed-" + release.Hash + ".txt"))) {
                        WriteLog(data, "This update failed to start previously; waiting for a newer release.");
                        return;
                    }
                    Stage(data, release, download);
                    WriteLog(data, "LightningRecaf " + release.Version.ToString(3) + " is ready for the next launch.");
                }
            } catch (Exception error) { WriteLog(data, "Update check skipped; installed version retained: " + error.Message); }
        }
        internal static void Stage(string data, Release release, Action<Release, string> download) {
            string root = Root(data), exe = Executable(root, release);
            Directory.CreateDirectory(Path.GetDirectoryName(exe));
            if (!ValidExecutable(exe, release)) {
                string partial = exe + ".partial-" + Guid.NewGuid().ToString("N");
                try {
                    download(release, partial);
                    if (!ValidExecutable(partial, release)) throw new InvalidDataException("Update checksum, size or version did not match the release.");
                    if (File.Exists(exe)) File.Replace(partial, exe, null); else File.Move(partial, exe);
                } finally { if (File.Exists(partial)) File.Delete(partial); }
            }
            string marker = Path.Combine(root, "ready.json"), temp = marker + "." + Guid.NewGuid().ToString("N");
            try {
                File.WriteAllText(temp, new JavaScriptSerializer().Serialize(new { version = release.Version.ToString(3), sha256 = release.Hash, size = release.Size }));
                if (File.Exists(marker)) File.Replace(temp, marker, null); else File.Move(temp, marker);
            } finally { if (File.Exists(temp)) File.Delete(temp); }
        }
        internal static bool AllowedDownload(Uri uri) {
            return uri.Scheme == Uri.UriSchemeHttps && uri.IsDefaultPort && uri.UserInfo.Length == 0
                && ((uri.Host == "github.com" && uri.AbsoluteUri.StartsWith(ReleasePrefix, StringComparison.Ordinal))
                    || uri.Host == "release-assets.githubusercontent.com" || uri.Host == "objects.githubusercontent.com");
        }
        static HttpWebRequest Request(string url, int timeout) {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            var request = (HttpWebRequest)WebRequest.Create(url);
            request.UserAgent = "LightningRecaf/" + CurrentVersion.ToString(3);
            request.Timeout = timeout; request.ReadWriteTimeout = timeout; request.AllowAutoRedirect = false;
            return request;
        }
        internal static string FetchLatest() {
            var request = Request(LatestUrl, 5000);
            request.Accept = "application/vnd.github+json";
            request.Headers["X-GitHub-Api-Version"] = "2022-11-28";
            using (var response = (HttpWebResponse)request.GetResponse())
            using (var input = response.GetResponseStream())
            using (var output = new MemoryStream()) {
                if (response.StatusCode != HttpStatusCode.OK) throw new IOException("Unexpected release response.");
                CopyLimited(input, output, 2 * 1024 * 1024, TimeSpan.FromSeconds(15));
                return Encoding.UTF8.GetString(output.ToArray());
            }
        }
        internal static void CopyLimited(Stream input, Stream output, long limit, TimeSpan timeout) {
            var watch = Stopwatch.StartNew(); var buffer = new byte[81920]; long count = 0; int read;
            while ((read = input.Read(buffer, 0, buffer.Length)) != 0) {
                count += read;
                if (count > limit || watch.Elapsed > timeout) throw new IOException("Update transfer exceeded its limit.");
                output.Write(buffer, 0, read);
            }
        }
        internal static void Download(Release release, string path) {
            Uri uri = new Uri(release.Url);
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (!AllowedDownload(uri)) throw new InvalidDataException("Update URL is outside the GitHub release service.");
                using (var response = (HttpWebResponse)Request(uri.AbsoluteUri, 15000).GetResponse()) {
                    int code = (int)response.StatusCode;
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        uri = new Uri(uri, response.Headers["Location"]); continue;
                    }
                    if (response.StatusCode != HttpStatusCode.OK || (response.ContentLength >= 0 && response.ContentLength != release.Size))
                        throw new InvalidDataException("Unexpected update download size or status.");
                    using (var input = response.GetResponseStream())
                    using (var output = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                        CopyLimited(input, output, release.Size, TimeSpan.FromMinutes(20));
                    return;
                }
            }
            throw new IOException("Too many update download redirects.");
        }
        internal static void WriteLog(string data, string message) {
            try {
                Directory.CreateDirectory(Root(data));
                string log = Path.Combine(Root(data), "updates.log");
                if (File.Exists(log) && new FileInfo(log).Length > 1024 * 1024) File.WriteAllText(log, "");
                File.AppendAllText(log, DateTime.UtcNow.ToString("o") + " " + message + Environment.NewLine);
            } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
}
