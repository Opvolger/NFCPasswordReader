package net.opvolger.nfcpasswordreader;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.LDSFileUtil;
import org.jmrtd.lds.icao.DG1File;
import org.jmrtd.lds.icao.DG2File;
import org.jmrtd.lds.icao.MRZInfo;
import org.jmrtd.lds.iso19794.FaceImageInfo;
import org.jmrtd.lds.iso19794.FaceInfo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, OutputFragment.OnFragmentInteractionListener, InputFragment.OnFragmentInteractionListener {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    Bitmap currentBitmap;
    String currentName;
    BACKeySpec currentbacKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);
        displayView(R.id.nav_input);
    }

    public void onResume() {
        super.onResume();

        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);

        IntentFilter ntech2 = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        IntentFilter ntech3 = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        IntentFilter[] filters = new IntentFilter[]{
                ntech3, ntech2,
        };

        String[][] techLists = new String[][]{new String[]{
                IsoDep.class.getName()}};
        if (adapter != null) {
            adapter.enableForegroundDispatch(this, pendingIntent, filters, techLists);
            if (!adapter.isEnabled()) {
                Toast.makeText(this, "please enable your nfc", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "your device do not have nfc.", Toast.LENGTH_SHORT).show();
        }
    }

    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resolveIntent(intent);
    }

    private void resolveIntent(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            PassportService ps = null;
            try {
                if (tag == null) {
                    return;
                }
                IsoDep nfc = IsoDep.get(tag);
                CardService cs = CardService.getInstance(nfc);
                ps = new PassportService(cs);
                ps.open();

                ps.sendSelectApplet(false);
                ps.doBAC(currentbacKey);

                InputStream is = null;
                InputStream isface = null;
                //InputStream is14 = null;
                //InputStream isCvca = null;
                try {
                    // Basic data
                    is = ps.getInputStream(PassportService.EF_DG1);
                    DG1File dg1 = (DG1File) LDSFileUtil.getLDSFile(PassportService.EF_DG1, is);
                    MRZInfo info = dg1.getMRZInfo();
                    currentName = info.getPrimaryIdentifier() + ' ' + info.getNationality();
                    Toast.makeText(this, dg1.getMRZInfo().getDateOfBirth(), Toast.LENGTH_LONG).show();
                    isface = ps.getInputStream(PassportService.EF_DG2);
                    DG2File dg2 = (DG2File) LDSFileUtil.getLDSFile(PassportService.EF_DG2, isface);
                    List<FaceInfo> faceInfos = dg2.getFaceInfos();
                    List<FaceImageInfo> allFaceImageInfos = new ArrayList<>();
                    for (FaceInfo faceInfo : faceInfos) {
                        allFaceImageInfos.addAll(faceInfo.getFaceImageInfos());
                    }

                    // momenteel alleen laatste plaatje, zou een array moeten zijn.
                    if (!allFaceImageInfos.isEmpty()) {
                        FaceImageInfo faceImageInfo = allFaceImageInfos.iterator().next();

                        int imageLength = faceImageInfo.getImageLength();
                        DataInputStream dataInputStream = new DataInputStream(faceImageInfo.getImageInputStream());
                        byte[] buffer = new byte[imageLength];
                        dataInputStream.readFully(buffer, 0, imageLength);
                        InputStream inputStream = new ByteArrayInputStream(buffer, 0, imageLength);
                        Bitmap face = null;
                        try {
                            Log.d("TEST", faceImageInfo.getMimeType());
                            // Je hebt ook nog iets dat geen jj2000 is.

                            org.jmrtd.jj2000.Bitmap bitmap = org.jmrtd.jj2000.JJ2000Decoder.decode(inputStream);
                            face = toAndroidBitmap(bitmap);
                            currentBitmap = face;
                        } catch (IOException e) {
                            e.printStackTrace();
                            displayView(R.id.nav_error);
                        }
                        // https://github.com/tananaev/passport-reader/
                        //bitmap = ImageUtil.decodeImage(
                        //        MainActivity.this, faceImageInfo.getMimeType(), inputStream);

                    }
                    /* Werkt nu niet geeft foutmeldingen
                    // Chip Authentication
                    is14 = ps.getInputStream(PassportService.EF_DG14);
                    DG14File dg14 = (DG14File) LDSFileUtil.getLDSFile(PassportService.EF_DG14, is14);
                    List<ChipAuthenticationPublicKeyInfo> keyInfo = dg14.getChipAuthenticationPublicKeyInfos();
                    ChipAuthenticationPublicKeyInfo entry = keyInfo.iterator().next();
                    Log.i("EMRTD", "Chip Authentication starting");
                    // verwijderd
                    Log.i("EMRTD", "Chip authentnication succeeded");

                    // CVCA
                    isCvca = ps.getInputStream(PassportService.EF_CVCA);
                    CVCAFile cvca = (CVCAFile) LDSFileUtil.getLDSFile(PassportService.EF_CVCA, isCvca);
                    // ik doe er niks mee.

                    //TODO EAC
                    */

                    // informatie is binnen start result:
                    displayView(R.id.nav_output);
                } catch (Exception e) {
                    e.printStackTrace();
                    displayView(R.id.nav_error);
                } finally {
                    try {
                        is.close();
                        isface.close();
                        //is14.close();
                        //isCvca.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                        displayView(R.id.nav_error);
                    }
                }
            } catch (CardServiceException e) {
                e.printStackTrace();
                displayView(R.id.nav_error);
            } catch (Exception ex) {
                displayView(R.id.nav_error);
            } finally {
                try {
                    ps.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    displayView(R.id.nav_error);
                }
            }
        }
    }

    private static Bitmap toAndroidBitmap(org.jmrtd.jj2000.Bitmap bitmap) {
        int[] intData = bitmap.getPixels();
        return Bitmap.createBitmap(intData, 0, bitmap.getWidth(), bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    public void onPause() {
        super.onPause();
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        nfcAdapter.disableForegroundDispatch(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void displayView(int viewId) {

        Fragment fragment = null;
        String title = getString(R.string.app_name);

        switch (viewId) {
            case R.id.nav_input:
                fragment = new InputFragment();
                title = "Input";
                break;
            case R.id.nav_output:
                fragment = new OutputFragment();
                title = "Output";
                break;
            case R.id.nav_error:
                fragment = new ErrorFragment();
                title = "Error";
                break;
        }

        if (fragment != null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.content_frame, fragment);
            ft.commit();
        }

        // set the toolbar title
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        displayView(id);

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onFragmentInteraction(Uri uri) {

    }
}
