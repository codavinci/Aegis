package me.impy.aegis;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.support.design.widget.BottomSheetDialog;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AlertDialog;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;

import me.impy.aegis.crypto.MasterKey;
import me.impy.aegis.db.DatabaseEntry;
import me.impy.aegis.db.DatabaseManager;
import me.impy.aegis.helpers.PermissionHelper;
import me.impy.aegis.importers.DatabaseImporter;
import me.impy.aegis.util.ByteInputStream;

public class MainActivity extends AegisActivity implements KeyProfileView.Listener {
    // activity request codes
    private static final int CODE_GET_KEYINFO = 0;
    private static final int CODE_ADD_KEYINFO = 1;
    private static final int CODE_EDIT_KEYINFO = 2;
    private static final int CODE_DO_INTRO = 3;
    private static final int CODE_DECRYPT = 4;
    private static final int CODE_IMPORT = 5;
    private static final int CODE_PREFERENCES = 6;

    // permission request codes
    private static final int CODE_PERM_EXPORT = 0;
    private static final int CODE_PERM_IMPORT = 1;
    private static final int CODE_PERM_CAMERA = 2;

    private AegisApplication _app;
    private DatabaseManager _db;
    private KeyProfileView _keyProfileView;

    private boolean _nightMode = false;
    private Menu _menu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _app = (AegisApplication) getApplication();
        _db = _app.getDatabaseManager();

        // set up the main view
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // set up the key profile view
        _keyProfileView = (KeyProfileView) getSupportFragmentManager().findFragmentById(R.id.key_profiles);
        _keyProfileView.setListener(this);
        _keyProfileView.setShowIssuer(_app.getPreferences().getBoolean("pref_issuer", false));

        // set up the floating action button
        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setEnabled(true);
        fab.setOnClickListener(view -> onGetKeyInfo());

        // skip this part if this is the not initial startup and the database has been unlocked
        if (!_app.isRunning() && _db.isLocked()) {
            if (!_db.fileExists()) {
                // the db doesn't exist, start the intro
                if (_app.getPreferences().getBoolean("pref_intro", false)) {
                    Toast.makeText(this, "Database file not found, starting over...", Toast.LENGTH_SHORT).show();
                }
                Intent intro = new Intent(this, IntroActivity.class);
                startActivityForResult(intro, CODE_DO_INTRO);
            } else {
                // the db exists, load the database
                // if the database is still encrypted, start the auth activity
                try {
                    if (!_db.isLoaded()) {
                        _db.load();
                    }
                    if (_db.isLocked()) {
                        startAuthActivity();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(this, "An error occurred while trying to deserialize the database", Toast.LENGTH_LONG).show();
                    throw new UndeclaredThrowableException(e);
                }
            }
        }

        // if the database has been decrypted at this point, we can load the key profiles
        if (!_db.isLocked()) {
            loadKeyProfiles();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (!doShortcutActions() || _db.isLocked()) {
            startAuthActivity();
        }
    }

    @Override
    protected void setPreferredTheme(boolean nightMode) {
        if (nightMode) {
            setTheme(R.style.AppTheme_Dark_NoActionBar);
        } else if (_nightMode) {
            setTheme(R.style.AppTheme_Default_NoActionBar);
        }
        _nightMode = nightMode;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data == null) {
            return;
        }

        switch (requestCode) {
            case CODE_GET_KEYINFO:
                onGetKeyInfoResult(resultCode, data);
                break;
            case CODE_ADD_KEYINFO:
                onAddKeyInfoResult(resultCode, data);
                break;
            case CODE_DO_INTRO:
                onDoIntroResult(resultCode, data);
                break;
            case CODE_DECRYPT:
                onDecryptResult(resultCode, data);
                break;
            case CODE_IMPORT:
                onImportResult(resultCode, data);
                break;
            case CODE_PREFERENCES:
                onPreferencesResult(resultCode, data);
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!PermissionHelper.checkResults(grantResults)) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            return;
        }

        switch (requestCode) {
            case CODE_PERM_EXPORT:
                onExport();
                break;
            case CODE_PERM_IMPORT:
                onImport();
                break;
            case CODE_PERM_CAMERA:
                onGetKeyInfo();
                break;
        }
    }

    private void onPreferencesResult(int resultCode, Intent data) {
        // refresh the entire key profile list if needed
        if (data.getBooleanExtra("needsRefresh", false)) {
            boolean showIssuer = _app.getPreferences().getBoolean("pref_issuer", false);
            _keyProfileView.setShowIssuer(showIssuer);
        }

        // perform any pending actions
        int action = data.getIntExtra("action", -1);
        switch (action) {
            case PreferencesActivity.ACTION_EXPORT:
                onExport();
                break;
        }
    }

    private void onExport() {
        if (!PermissionHelper.request(this, CODE_PERM_EXPORT, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return;
        }

        // TODO: create a custom layout to show a message AND a checkbox
        final boolean[] checked = {true};
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this)
                .setTitle("Export the database")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String filename;
                    try {
                        filename = _db.export(checked[0]);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "An error occurred while trying to export the database", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // make sure the new file is visible
                    MediaScannerConnection.scanFile(this, new String[]{filename}, null, null);

                    Toast.makeText(this, "The database has been exported to: " + filename, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (_db.getFile().isEncrypted()) {
            final String[] items = {"Keep the database encrypted"};
            final boolean[] checkedItems = {true};
            builder.setMultiChoiceItems(items, checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int index, boolean isChecked) {
                    checked[0] = isChecked;
                }
            });
        } else {
            builder.setMessage("This action will export the database out of Android's private storage.");
        }
        builder.show();
    }

    private void onImport() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, CODE_IMPORT);
    }

    private void onImportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }

        InputStream fileStream = null;
        try {
            try {
                fileStream = getContentResolver().openInputStream(data.getData());
            } catch (Exception e) {
                Toast.makeText(this, "An error occurred while trying to open the file", Toast.LENGTH_SHORT).show();
                return;
            }

            ByteInputStream stream;
            try {
                int read;
                byte[] buf = new byte[4096];
                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                while ((read = fileStream.read(buf, 0, buf.length)) != -1) {
                    outStream.write(buf, 0, read);
                }
                stream = new ByteInputStream(outStream.toByteArray());
            } catch (Exception e) {
                Toast.makeText(this, "An error occurred while trying to read the file", Toast.LENGTH_SHORT).show();
                return;
            }

            List<DatabaseEntry> entries = null;
            for (DatabaseImporter converter : DatabaseImporter.create(stream)) {
                try {
                    entries = converter.convert();
                    break;
                } catch (Exception e) {
                    stream.reset();
                }
            }

            if (entries == null) {
                Toast.makeText(this, "An error occurred while trying to parse the file", Toast.LENGTH_SHORT).show();
                return;
            }

            for (DatabaseEntry entry : entries) {
                addKey(new KeyProfile(entry));
            }
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (Exception e) {
                }
            }
        }

        saveDatabase();
    }

    private void onGetKeyInfo() {
        if (!PermissionHelper.request(this, CODE_PERM_CAMERA, Manifest.permission.CAMERA)) {
            return;
        }

        startScanActivity();
    }

    private void onGetKeyInfoResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            KeyProfile keyProfile = (KeyProfile)data.getSerializableExtra("KeyProfile");
            Intent intent = new Intent(this, AddProfileActivity.class);
            intent.putExtra("KeyProfile", keyProfile);
            startActivityForResult(intent, CODE_ADD_KEYINFO);
        }
    }

    private void onAddKeyInfoResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            KeyProfile profile = (KeyProfile) data.getSerializableExtra("KeyProfile");
            addKey(profile);
            saveDatabase();
        }
    }

    private void addKey(KeyProfile profile) {
        profile.refreshCode();

        DatabaseEntry entry = profile.getEntry();
        entry.setName(entry.getInfo().getAccountName());
        try {
            _db.addKey(entry);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "An error occurred while trying to add an entry", Toast.LENGTH_SHORT).show();
            return;
        }

        _keyProfileView.addKey(profile);
    }

    private void onDoIntroResult(int resultCode, Intent data) {
        if (resultCode == IntroActivity.RESULT_EXCEPTION) {
            // TODO: user feedback
            Exception e = (Exception) data.getSerializableExtra("exception");
            throw new UndeclaredThrowableException(e);
        }

        MasterKey key = (MasterKey) data.getSerializableExtra("key");
        try {
            _db.load();
            if (_db.isLocked()) {
                _db.unlock(key);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "An error occurred while trying to load/decrypt the database", Toast.LENGTH_LONG).show();
            startAuthActivity();
            return;
        }

        loadKeyProfiles();
    }

    private void onDecryptResult(int resultCode, Intent intent) {
        MasterKey key = (MasterKey) intent.getSerializableExtra("key");
        try {
            _db.unlock(key);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "An error occurred while trying to decrypt the database", Toast.LENGTH_LONG).show();
            startAuthActivity();
            return;
        }

        loadKeyProfiles();
        doShortcutActions();
    }

    private void startScanActivity() {
        Intent scannerActivity = new Intent(getApplicationContext(), ScannerActivity.class);
        startActivityForResult(scannerActivity, CODE_GET_KEYINFO);
    }

    private boolean doShortcutActions() {
        // return false if an action was blocked by a locked database
        // otherwise, always return true
        Intent intent = getIntent();
        String action = intent.getStringExtra("action");
        if (action == null) {
            return true;
        } else if (_db.isLocked()) {
            return false;
        }

        switch (action) {
            case "scan":
                startScanActivity();
                break;
        }

        intent.removeExtra("action");
        return true;
    }

    public void startActivityForResult(Intent intent, int requestCode) {
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        super.startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onResume() {
        super.onResume();

        boolean nightMode = _app.getPreferences().getBoolean("pref_night_mode", false);
        if (nightMode != _nightMode) {
            setPreferredTheme(nightMode);
            recreate();
        }
    }

    private BottomSheetDialog createBottomSheet(final KeyProfile profile) {
        View bottomSheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_edit_profile, null);
        LinearLayout copyLayout = bottomSheetView.findViewById(R.id.copy_button);
        LinearLayout deleteLayout = bottomSheetView.findViewById(R.id.delete_button);
        LinearLayout editLayout = bottomSheetView.findViewById(R.id.edit_button);
        bottomSheetView.findViewById(R.id.edit_button);
        BottomSheetDialog bottomDialog = new BottomSheetDialog(this);
        bottomDialog.setContentView(bottomSheetView);
        bottomDialog.setCancelable(true);
        bottomDialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomDialog.show();

        copyLayout.setOnClickListener(view -> {
            bottomDialog.dismiss();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("text/plain", profile.getCode());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this.getApplicationContext(), "Code copied to the clipboard", Toast.LENGTH_SHORT).show();
        });

        deleteLayout.setOnClickListener(view -> {
            bottomDialog.dismiss();
            deleteProfile(profile);
        });

        editLayout.setOnClickListener(view -> {
            bottomDialog.dismiss();
            Intent intent = new Intent(this, EditProfileActivity.class);
            intent.putExtra("KeyProfile", profile);
            startActivityForResult(intent, CODE_EDIT_KEYINFO);
        });

        return bottomDialog;
    }

    private void deleteProfile(KeyProfile profile) {
        new AlertDialog.Builder(MainActivity.this)
            .setTitle("Delete entry")
            .setMessage("Are you sure you want to delete this profile?")
            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                try {
                    _db.removeKey(profile.getEntry());
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(this, "An error occurred while trying to delete an entry", Toast.LENGTH_SHORT).show();
                    return;
                }
                saveDatabase();

                _keyProfileView.removeKey(profile);
            })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        _menu = menu;
        getMenuInflater().inflate(R.menu.menu_main, menu);
        updateLockIcon();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent preferencesActivity = new Intent(this, PreferencesActivity.class);
                startActivityForResult(preferencesActivity, CODE_PREFERENCES);
                return true;
            case R.id.action_import:
                if (PermissionHelper.request(this, CODE_PERM_IMPORT, Manifest.permission.CAMERA)) {
                    onImport();
                }
                return true;
            case R.id.action_lock:
                _keyProfileView.clearKeys();
                try {
                    _db.lock();
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(this, "An error occurred while trying to lock the database", Toast.LENGTH_LONG).show();
                }
                startAuthActivity();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void startAuthActivity() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.putExtra("slots", _db.getFile().getSlots());
        startActivityForResult(intent, CODE_DECRYPT);
    }

    private void saveDatabase() {
        try {
            _db.save();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "An error occurred while trying to save the database", Toast.LENGTH_LONG).show();
        }
    }

    private void loadKeyProfiles() {
        updateLockIcon();

        try {
            for (DatabaseEntry entry : _db.getKeys()) {
                _keyProfileView.addKey(new KeyProfile(entry));
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "An error occurred while trying to load database entries", Toast.LENGTH_SHORT).show();
            return;
        }
    }

    private void updateLockIcon() {
        // hide the lock icon if the database is not unlocked
        if (_menu != null && !_db.isLocked()) {
            MenuItem item = _menu.findItem(R.id.action_lock);
            item.setVisible(_db.getFile().isEncrypted());
        }
    }

    @Override
    public void onEntryClick(KeyProfile profile) {
        createBottomSheet(profile).show();
    }

    @Override
    public void onEntryMove(DatabaseEntry entry1, DatabaseEntry entry2) {
        try {
            _db.swapKeys(entry1, entry2);
        } catch (Exception e) {
            e.printStackTrace();
            throw new UndeclaredThrowableException(e);
        }
    }

    @Override
    public void onEntryDrop(DatabaseEntry entry) {
        saveDatabase();
    }
}
