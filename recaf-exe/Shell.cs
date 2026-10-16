// Windows shell integration for LightningRecaf.exe: taskbar identity + grouping, jump list, Start Menu shortcut,
// "Open with LightningRecaf" context menu entries.

using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;

namespace RecafExe
{
    static class ShellIntegration
    {
        public const string AppId = "LightningRecaf.Workbench";
        const string AppRegistration = @"Software\Classes\Applications\LightningRecaf.exe";
        const string Capabilities = @"Software\LightningRecaf\Capabilities";
        static readonly string[] Extensions = { ".jar", ".class", ".apk", ".dex", ".war", ".zip", ".jmod", ".ear", ".aar" };

        /// <summary>Set by tests to install for a different exe than the running one.</summary>
        public static string ExePathOverride;

        public static string ExePath { get { return ExePathOverride ?? LightningRecaf.Updates.AppUpdates.EntryExecutable(System.Windows.Forms.Application.ExecutablePath); } }

        /// <summary>Gives this process an explicit AppUserModelID so Recaf's window can be grouped under it.</summary>
        public static void SetProcessAppId()
        {
            try { SetCurrentProcessExplicitAppUserModelID(AppId); } catch { }
        }

        /// <summary>Everything that should be in place once per exe location: shortcut, context menu, jump list.</summary>
        public static bool EnsureInstalled(ExeConfig config, bool force)
        {
            if (Paths.IsPortable && !force) return true;
            try
            {
                Directory.CreateDirectory(Paths.LauncherDir);
                string marker = Path.Combine(Paths.LauncherDir, "lightning-integration.txt");
                string current = ExePath + "|5";
                string previous = File.Exists(marker) ? File.ReadAllText(marker).Trim() : "";
                if (force || previous != current)
                {
                    if (config.GetBool("start-menu-shortcut", true)) EnsureStartMenuShortcut();
                    if (config.GetBool("context-menu", true)) RegisterContextMenu();
                    if (config.GetBool("open-with", true)) RegisterOpenWith();
                    File.WriteAllText(marker, current);
                    SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero);
                }
            }
            catch (Exception e)
            {
                Log.Error("Shell integration failed", e);
                return false;
            }
            try { UpdateJumpList(); } catch (Exception e) { Log.Error("Jump list update failed", e); }
            return true;
        }

        public static void Uninstall()
        {
            try
            {
                foreach (var ext in Extensions)
                {
                    try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\SystemFileAssociations\" + ext + @"\shell\LightningRecaf.Open", false); } catch { }
                    using (var openWith = Registry.CurrentUser.OpenSubKey(@"Software\Classes\" + ext + @"\OpenWithProgids", true))
                        if (openWith != null) openWith.DeleteValue(ProgId(ext), false);
                    Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\" + ProgId(ext), false);
                }
                Registry.CurrentUser.DeleteSubKeyTree(AppRegistration, false);
                Registry.CurrentUser.DeleteSubKeyTree(Capabilities, false);
                using (var registered = Registry.CurrentUser.OpenSubKey(@"Software\RegisteredApplications", true))
                    if (registered != null) registered.DeleteValue("LightningRecaf", false);
                try { File.Delete(StartMenuShortcutPath); } catch { }
                try { File.Delete(Path.Combine(Paths.LauncherDir, "lightning-integration.txt")); } catch { }
                SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero);
                try
                {
                    var list = (ICustomDestinationList)new DestinationList();
                    list.SetAppID(AppId);
                    list.DeleteList(AppId);
                }
                catch { }
            }
            catch (Exception e)
            {
                Log.Error("Shell integration removal failed", e);
            }
        }

        // --- Start Menu shortcut (carries the AppUserModelID so a pinned taskbar button groups with the running window)

        static string StartMenuShortcutPath
        {
            get
            {
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "LightningRecaf.lnk");
            }
        }

        static void EnsureStartMenuShortcut()
        {
            var link = (IShellLinkW)new ShellLink();
            link.SetPath(ExePath);
            link.SetWorkingDirectory(Path.GetDirectoryName(ExePath));
            link.SetDescription("LightningRecaf - Java reverse engineering workbench");
            link.SetIconLocation(ExePath, 0);
            var store = (IPropertyStore)link;
            SetString(store, PropertyKeys.AppUserModelId, AppId);
            store.Commit();
            ((IPersistFile)link).Save(StartMenuShortcutPath, true);
            Log.Info("Start Menu shortcut written: " + StartMenuShortcutPath);
        }

        // --- Context menu ---------------------------------------------------------------------------------

        static void RegisterContextMenu()
        {
            foreach (var ext in Extensions)
            {
                using (var shell = Registry.CurrentUser.CreateSubKey(@"Software\Classes\SystemFileAssociations\" + ext + @"\shell\LightningRecaf.Open"))
                {
                    if (shell == null) continue;
                    shell.SetValue("", "Open with LightningRecaf");
                    shell.SetValue("MultiSelectModel", "Single");
                    shell.SetValue("Icon", "\"" + ExePath + "\",0");
                    using (var cmd = shell.CreateSubKey("command"))
                        if (cmd != null) cmd.SetValue("", OpenCommand);
                }
            }
            Log.Info("Context menu entries registered for " + string.Join(" ", Extensions));
        }

        static string ProgId(string extension) { return "LightningRecaf" + extension.ToUpperInvariant(); }
        static string OpenCommand { get { return Native.Quote(ExePath) + " \"%1\""; } }
        static void RegisterOpenWith()
        {
            using (var app = Registry.CurrentUser.CreateSubKey(AppRegistration))
            {
                app.SetValue("FriendlyAppName", "LightningRecaf");
                using (var command = app.CreateSubKey(@"shell\open\command")) command.SetValue("", OpenCommand);
                using (var types = app.CreateSubKey("SupportedTypes")) foreach (var ext in Extensions) types.SetValue(ext, "");
            }
            using (var capabilities = Registry.CurrentUser.CreateSubKey(Capabilities))
            {
                capabilities.SetValue("ApplicationName", "LightningRecaf");
                capabilities.SetValue("ApplicationDescription", "Inspect and reverse Java bytecode and archives with LightningRecaf.");
                capabilities.SetValue("ApplicationIcon", Native.Quote(ExePath) + ",0");
                using (var types = capabilities.CreateSubKey("FileAssociations"))
                    foreach (var ext in Extensions) types.SetValue(ext, ProgId(ext));
            }
            using (var registered = Registry.CurrentUser.CreateSubKey(@"Software\RegisteredApplications"))
                registered.SetValue("LightningRecaf", Capabilities);
            foreach (var ext in Extensions)
            {
                using (var prog = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + ProgId(ext)))
                {
                    prog.SetValue("", "LightningRecaf " + ext.Substring(1).ToUpperInvariant() + " document");
                    prog.SetValue("FriendlyTypeName", "Java bytecode / archive");
                    using (var icon = prog.CreateSubKey("DefaultIcon")) icon.SetValue("", Native.Quote(ExePath) + ",0");
                    using (var command = prog.CreateSubKey(@"shell\open\command")) command.SetValue("", OpenCommand);
                }
                using (var types = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + ext + @"\OpenWithProgids"))
                    types.SetValue(ProgId(ext), new byte[0], RegistryValueKind.None);
            }
            // Windows owns default-app selection. Never overwrite UserChoice or the user's existing .jar handler.
        }

        // --- Jump list ------------------------------------------------------------------------------------

        public static void UpdateJumpList()
        {
            var list = (ICustomDestinationList)new DestinationList();
            list.SetAppID(AppId);
            uint slots;
            object removedObj;
            Guid arrayIid = typeof(IObjectArray).GUID;
            list.BeginList(out slots, ref arrayIid, out removedObj);
            var removedArguments = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var removed = removedObj as IObjectArray;
            if (removed != null) {
                uint count; removed.GetCount(out count);
                for (uint i = 0; i < count; i++) {
                    try {
                        object entry; Guid linkId = typeof(IShellLinkW).GUID;
                        removed.GetAt(i, ref linkId, out entry);
                        var args = new StringBuilder(32768);
                        ((IShellLinkW)entry).GetArguments(args, args.Capacity);
                        removedArguments.Add(args.ToString());
                    } catch (COMException) { }
                }
            }

            var tasks = (IObjectCollection)new EnumerableObjectCollection();
            tasks.AddObject(MakeLink("Open LightningRecaf", "", 0));
            list.AddUserTasks((IObjectArray)tasks);

            // Recent workspaces from Recaf's own config.
            var recent = ReadRecentWorkspaces();
            if (recent.Count > 0)
            {
                var items = (IObjectCollection)new EnumerableObjectCollection();
                int count = 0;
                foreach (var path in recent)
                {
                    if (removedArguments.Contains(Native.Quote(path))) continue;
                    if (count++ >= Math.Max(1, Math.Min((int)slots, 10))) break;
                    string title = Path.GetFileName(path.TrimEnd('\\', '/'));
                    if (string.IsNullOrEmpty(title)) title = path;
                    items.AddObject(MakeLink(title, Native.Quote(path), 0, path));
                }
                if (count > 0) list.AppendCategory("Recent workspaces", (IObjectArray)items);
            }
            list.CommitList();
        }

        static List<string> ReadRecentWorkspaces()
        {
            var result = new List<string>();
            foreach (var profile in new[] { Paths.CustomDataDir })
            {
            try
            {
                string file = Path.Combine(profile, "config", "service.io.recent-workspaces-config.json");
                if (!File.Exists(file)) continue;
                object root = Json.Parse(File.ReadAllText(file));
                foreach (object ws in Json.Array(root, "recent-workspaces"))
                {
                    object primary = Json.Get(ws, "primary");
                    string path = Json.Str(primary, "path", null);
                    if (string.IsNullOrEmpty(path)) continue;
                    path = path.Replace('/', '\\');
                    if ((File.Exists(path) || Directory.Exists(path)) && !result.Contains(path)) result.Add(path);
                }
            }
            catch (Exception e)
            {
                Log.Error("Could not read recent workspaces", e);
            }
            }
            return result;
        }

        static IShellLinkW MakeLink(string title, string arguments, int iconIndex, string description = null)
        {
            var link = (IShellLinkW)new ShellLink();
            link.SetPath(ExePath);
            link.SetArguments(arguments);
            link.SetWorkingDirectory(Path.GetDirectoryName(ExePath));
            link.SetIconLocation(ExePath, iconIndex);
            if (description != null) link.SetDescription(description);
            var store = (IPropertyStore)link;
            SetString(store, PropertyKeys.Title, title);
            store.Commit();
            return link;
        }

        // --- Window identity (groups Recaf's Java window under the exe's taskbar button) --------------------

        public static void ApplyWindowProperties(IntPtr hwnd)
        {
            try
            {
                Guid iid = typeof(IPropertyStore).GUID;
                IPropertyStore store;
                int hr = SHGetPropertyStoreForWindow(hwnd, ref iid, out store);
                if (hr != 0 || store == null) return;
                SetString(store, PropertyKeys.AppUserModelId, AppId);
                SetString(store, PropertyKeys.RelaunchCommand, Native.Quote(ExePath));
                SetString(store, PropertyKeys.RelaunchDisplayNameResource, "LightningRecaf");
                SetString(store, PropertyKeys.RelaunchIconResource, ExePath + ",0");
                store.Commit();
                Marshal.ReleaseComObject(store);
            }
            catch (Exception e)
            {
                Log.Error("Could not set window taskbar identity", e);
            }
        }

        static void SetString(IPropertyStore store, PROPERTYKEY key, string value)
        {
            var pv = new PROPVARIANT();
            pv.vt = 31; // VT_LPWSTR
            pv.p = Marshal.StringToCoTaskMemUni(value);
            try { store.SetValue(ref key, ref pv); }
            finally { PropVariantClear(ref pv); }
        }

        // --- Interop ----------------------------------------------------------------------------------------

        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        static extern int SetCurrentProcessExplicitAppUserModelID(string appId);

        [DllImport("shell32.dll")]
        static extern void SHChangeNotify(uint eventId, uint flags, IntPtr item1, IntPtr item2);

        [DllImport("shell32.dll")]
        static extern int SHGetPropertyStoreForWindow(IntPtr hwnd, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IPropertyStore store);

        [DllImport("ole32.dll")]
        static extern int PropVariantClear(ref PROPVARIANT pvar);

        [StructLayout(LayoutKind.Sequential)]
        struct PROPERTYKEY
        {
            public Guid fmtid;
            public uint pid;
            public PROPERTYKEY(string guid, uint pid) { fmtid = new Guid(guid); this.pid = pid; }
        }

        static class PropertyKeys
        {
            public static readonly PROPERTYKEY Title = new PROPERTYKEY("F29F85E0-4FF9-1068-AB91-08002B27B3D9", 2);
            public static readonly PROPERTYKEY AppUserModelId = new PROPERTYKEY("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3", 5);
            public static readonly PROPERTYKEY RelaunchCommand = new PROPERTYKEY("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3", 2);
            public static readonly PROPERTYKEY RelaunchIconResource = new PROPERTYKEY("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3", 3);
            public static readonly PROPERTYKEY RelaunchDisplayNameResource = new PROPERTYKEY("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3", 4);
        }

        [StructLayout(LayoutKind.Sequential)]
        struct PROPVARIANT
        {
            public ushort vt;
            public ushort r1, r2, r3;
            public IntPtr p;
            public IntPtr p2;
        }

        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
        class ShellLink { }

        [ComImport, Guid("77f10cf0-3db5-4966-b520-b7c54fd35ed6")]
        class DestinationList { }

        [ComImport, Guid("2d3468c1-36a7-43b6-ac24-d3f02fd9607a")]
        class EnumerableObjectCollection { }

        [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IShellLinkW
        {
            void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cch, IntPtr pfd, uint fFlags);
            void GetIDList(out IntPtr ppidl);
            void SetIDList(IntPtr pidl);
            void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cch);
            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
            void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cch);
            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
            void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cch);
            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
            void GetHotkey(out short pwHotkey);
            void SetHotkey(short wHotkey);
            void GetShowCmd(out int piShowCmd);
            void SetShowCmd(int iShowCmd);
            void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath, int cch, out int piIcon);
            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, uint dwReserved);
            void Resolve(IntPtr hwnd, uint fFlags);
            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
        }

        [ComImport, Guid("0000010b-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IPersistFile
        {
            void GetClassID(out Guid pClassID);
            [PreserveSig] int IsDirty();
            void Load([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, uint dwMode);
            void Save([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, [MarshalAs(UnmanagedType.Bool)] bool fRemember);
            void SaveCompleted([MarshalAs(UnmanagedType.LPWStr)] string pszFileName);
            void GetCurFile(out IntPtr ppszFileName);
        }

        [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IPropertyStore
        {
            void GetCount(out uint cProps);
            void GetAt(uint iProp, out PROPERTYKEY pkey);
            void GetValue(ref PROPERTYKEY key, out PROPVARIANT pv);
            void SetValue(ref PROPERTYKEY key, ref PROPVARIANT pv);
            void Commit();
        }

        [ComImport, Guid("92CA9DCD-5622-4bba-A805-5E9F541BD8C9"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IObjectArray
        {
            void GetCount(out uint cObjects);
            void GetAt(uint iIndex, ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppv);
        }

        [ComImport, Guid("5632b1a4-e38a-400a-928a-d4cd63230295"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IObjectCollection
        {
            void GetCount(out uint cObjects);
            void GetAt(uint iIndex, ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppv);
            void AddObject([MarshalAs(UnmanagedType.IUnknown)] object punk);
            void AddFromArray(IObjectArray poaSource);
            void RemoveObjectAt(uint uiIndex);
            void Clear();
        }

        [ComImport, Guid("6332DEBF-87B5-4670-90C0-5E57B408A49E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface ICustomDestinationList
        {
            void SetAppID([MarshalAs(UnmanagedType.LPWStr)] string pszAppID);
            void BeginList(out uint pcMinSlots, ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppv);
            void AppendCategory([MarshalAs(UnmanagedType.LPWStr)] string pszCategory, IObjectArray poa);
            void AppendKnownCategory(int category);
            void AddUserTasks(IObjectArray poa);
            void CommitList();
            void GetRemovedDestinations(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppv);
            void DeleteList([MarshalAs(UnmanagedType.LPWStr)] string pszAppID);
            void AbortList();
        }
    }
}
