package com.dx168.patchsdk;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.dx168.patchsdk.bean.AppInfo;
import com.dx168.patchsdk.bean.PatchInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Set;

import okhttp3.ResponseBody;
import rx.Subscriber;
import rx.schedulers.Schedulers;

/**
 * Created by jianjun.lin on 2016/10/26.
 */
public final class PatchManager {

    private static final String TAG = "PatchManager";

    private static PatchManager instance;

    public static PatchManager getInstance() {
        if (instance == null) {
            instance = new PatchManager();
        }
        return instance;
    }

    public static void free() {
        instance = null;
        PatchServer.free();
    }

    public static final String SP_NAME = "patchsdk";
    public static final String SP_KEY_USING_PATCH = "using_patch";

    private Context context;
    private ActualPatchManager apm;
    private String patchDirPath;
    private AppInfo appInfo;

    private PatchInfo patchInfo;

    public void init(Context context, String baseUrl, String appId, String appSecret, ActualPatchManager apm) {
        this.context = context;
        if (!PatchUtils.isMainProcess(context)) {
            return;
        }
        this.apm = apm;
        appInfo = new AppInfo();
        appInfo.setAppId(appId);
        appInfo.setAppSecret(appSecret);
        appInfo.setToken(DigestUtils.md5DigestAsHex(appId + "_" + appSecret));
        appInfo.setDeviceId(PatchUtils.getDeviceId(context));
        PatchServer.init(baseUrl);
        PackageManager packageManager = context.getPackageManager();
        try {
            PackageInfo pkgInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
            appInfo.setVersionName(pkgInfo.versionName);
            appInfo.setVersionCode(pkgInfo.versionCode);
            String hotFixDirPath = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + context.getPackageName() + File.separator + "patchsdk";
            patchDirPath = hotFixDirPath + File.separator + appInfo.getVersionName();
            File hotFixDir = new File(hotFixDirPath);
            if (hotFixDir.exists()) {
                for (File patchDir : hotFixDir.listFiles()) {
                    if (TextUtils.equals(appInfo.getVersionName(), patchDir.getName())) {
                        continue;
                    }
                    patchDir.delete();
                }
                SharedPreferences sp = context.getSharedPreferences(PatchManager.SP_NAME, Context.MODE_PRIVATE);
                Set<String> spKeys = sp.getAll().keySet();
                SharedPreferences.Editor editor = sp.edit();
                for (String key : spKeys) {
                    if (key.startsWith(appInfo.getVersionName()) || TextUtils.equals(SP_KEY_USING_PATCH, key)) {
                        continue;
                    }
                    editor.remove(key);
                }
                editor.commit();
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void setTag(String tag) {
        if (context == null) {
            return;
            //throw new NullPointerException("PatchManager must be init before using");
        }
        if (!PatchUtils.isMainProcess(context)) {
            return;
        }
        appInfo.setTag(tag);
    }

    public void setChannel(String channel) {
        if (context == null) {
            return;
            //throw new NullPointerException("PatchManager must be init before using");
        }
        if (!PatchUtils.isMainProcess(context)) {
            return;
        }
        appInfo.setChannel(channel);
    }

    private PatchListener patchListener;

    public void queryAndApplyPatch() {
        queryAndApplyPatch(null);
    }

    public void queryAndApplyPatch(PatchListener listener) {
        if (context == null) {
            throw new NullPointerException("PatchManager must be init before using");
        }
        if (!PatchUtils.isMainProcess(context)) {
            return;
        }
        this.patchListener = listener;
        PatchServer.get()
                .queryPatch(appInfo.getAppId(), appInfo.getToken(), appInfo.getTag(),
                        appInfo.getVersionName(), appInfo.getVersionCode(), appInfo.getPlatform(),
                        appInfo.getOsVersion(), appInfo.getModel(), appInfo.getChannel(),
                        appInfo.getSdkVersion(), appInfo.getDeviceId())
                .subscribeOn(Schedulers.io())
                .subscribe(new Subscriber<PatchInfo>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        if (patchListener != null) {
                            patchListener.onQueryFailure(e);
                        }
                    }

                    @Override
                    public void onNext(PatchInfo patchInfo) {
                        PatchManager.this.patchInfo = patchInfo;
                        if (patchInfo.getCode() != 200) {
                            if (patchListener != null) {
                                patchListener.onQueryFailure(new Exception("code=" + patchInfo.getCode()));
                            }
                            return;
                        }
                        if (patchListener != null) {
                            patchListener.onQuerySuccess(patchInfo.toString());
                        }
                        if (patchInfo.getData() == null) {
                            File patchDir = new File(patchDirPath);
                            if (patchDir.exists()) {
                                patchDir.delete();
                            }
                            apm.cleanPatch(context);
                            if (patchListener != null) {
                                patchListener.onCompleted();
                            }
                            return;
                        }
                        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                        String usingPatchPath = sp.getString(SP_KEY_USING_PATCH, "");
                        String newPatchPath = getPatchPath(patchInfo.getData());
                        if (TextUtils.equals(usingPatchPath, newPatchPath)) {
                            if (patchListener != null) {
                                patchListener.onCompleted();
                            }
                            return;
                        }
                        File patchDir = new File(patchDirPath);
                        if (patchDir.exists()) {
                            for (File patch : patchDir.listFiles()) {
                                String patchName = getPatchName(patchInfo.getData());
                                if (TextUtils.equals(patch.getName(), patchName)) {
                                    if (!checkPatch(patch, patchInfo.getData().getHash())) {
                                        Log.e(TAG, "cache patch's hash is wrong");
                                        if (patchListener != null) {
                                            patchListener.onDownloadFailure(new Exception("cache patch's hash is wrong"));
                                        }
                                        return;
                                    }
                                    apm.cleanPatch(context);
                                    apm.applyPatch(context, patch.getAbsolutePath());
                                    return;
                                }
                            }
                        }
                        downloadAndApplyPatch(newPatchPath, patchInfo.getData().getDownloadUrl(), patchInfo.getData().getHash());
                    }

                });

    }

    private void downloadAndApplyPatch(final String newPatchPath, String url, final String hash) {
        PatchServer.get()
                .downloadFile(url)
                .subscribeOn(Schedulers.io())
                .subscribe(new Subscriber<ResponseBody>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        if (patchListener != null) {
                            patchListener.onDownloadFailure(e);
                        }
                    }

                    @Override
                    public void onNext(ResponseBody body) {
                        byte[] bytes = null;
                        try {
                            bytes = body.bytes();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        if (!checkPatch(bytes, hash)) {
                            Log.e(TAG, "downloaded patch's hash is wrong");
                            if (patchListener != null) {
                                patchListener.onDownloadFailure(new Exception("downloaded patch's hash is wrong"));
                            }
                            return;
                        }
                        PatchUtils.writeToDisk(bytes, newPatchPath);
                        if (patchListener != null) {
                            patchListener.onDownloadSuccess(newPatchPath);
                        }
                        apm.applyPatch(context, newPatchPath);
                    }
                });
    }

    private boolean checkPatch(File patch, String hash) {
        FileInputStream fis = null;
        ByteArrayOutputStream bos = null;
        byte[] bytes = null;
        try {
            fis = new FileInputStream(patch);
            bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int len;
            while ((len = fis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            bytes = bos.toByteArray();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return checkPatch(bytes, hash);
    }

    private boolean checkPatch(byte[] bytes, String hash) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        String downloadFileHash = DigestUtils.md5DigestAsHex(appInfo.getAppId() + "_" + appInfo.getAppSecret() + "_" + DigestUtils.md5DigestAsHex(bytes));
        return TextUtils.equals(downloadFileHash, hash);
    }

    private String getPatchPath(PatchInfo.Data data) {
        return patchDirPath + File.separator + getPatchName(data);
    }

    private String getPatchName(PatchInfo.Data data) {
        return data.getPatchVersion() + "_" + data.getHash() + ".apk";
    }

    public void onApplySuccess() {
        String patchPath = getPatchPath(patchInfo.getData());
        SharedPreferences sp = context.getSharedPreferences(PatchManager.SP_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(PatchManager.SP_KEY_USING_PATCH, patchPath);
        editor.apply();
        report(true);
        if (patchListener != null) {
            patchListener.onApplySuccess();
            patchListener.onCompleted();
        }
    }

    public void onApplyFailure(String msg) {
        report(false);
        if (patchListener != null) {
            patchListener.onApplyFailure(msg);
        }
    }

    private static final int APPLY_SUCCESS_REPORTED = 1;
    private static final int APPLY_FAILURE_REPORTED = 2;

    private void report(final boolean applyResult) {
        if (patchInfo == null) {
            return;
        }
        SharedPreferences sp = context.getSharedPreferences(PatchManager.SP_NAME, Context.MODE_PRIVATE);
        final String patchName = appInfo.getVersionName() + "_" + getPatchName(patchInfo.getData());
        int flag = sp.getInt(patchName, -1);
        /**
         * 如果已经上报过成功，不管本次是否修复成功，都不上报
         * 如果已经上报过失败，且本次修复成功，则上报成功
         * 如果已经上报过失败，且本次修复失败，则不上报
         */
        if (flag == APPLY_SUCCESS_REPORTED
                || (!applyResult && flag == APPLY_FAILURE_REPORTED)) {
            return;
        }
        PatchServer.get()
                .report(appInfo.getAppId(), appInfo.getToken(), appInfo.getTag(),
                        appInfo.getVersionName(), appInfo.getVersionCode(), appInfo.getPlatform(),
                        appInfo.getOsVersion(), appInfo.getModel(), appInfo.getChannel(),
                        appInfo.getSdkVersion(), appInfo.getDeviceId(), patchInfo.getData().getUid(),
                        applyResult)
                .subscribeOn(Schedulers.io())
                .subscribe(new Subscriber<Void>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onNext(Void aVoid) {
                        SharedPreferences sp = context.getSharedPreferences(PatchManager.SP_NAME, Context.MODE_PRIVATE);
                        int flag;
                        if (applyResult) {
                            flag = APPLY_SUCCESS_REPORTED;
                        } else {
                            flag = APPLY_FAILURE_REPORTED;
                        }
                        sp.edit().putInt(patchName, flag).apply();
                    }
                });
    }

}
