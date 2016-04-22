    package com.codeboy.qianghongbao.job;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.codeboy.qianghongbao.BuildConfig;
import com.codeboy.qianghongbao.Config;
import com.codeboy.qianghongbao.IStatusBarNotification;
import com.codeboy.qianghongbao.QHBApplication;
import com.codeboy.qianghongbao.QiangHongBaoService;
import com.codeboy.qianghongbao.util.AccessibilityHelper;
import com.codeboy.qianghongbao.util.NotifyHelper;

import java.util.List;

/**
 * <p>Created 16/1/16 上午12:40.</p>
 * <p><a href="mailto:codeboy2013@gmail.com">Email:codeboy2013@gmail.com</a></p>
 * <p><a href="http://www.happycodeboy.com">LeonLee Blog</a></p>
 *
 * @author LeonLee
 */
public class WechatAccessbilityJob extends BaseAccessbilityJob {

    private static final String TAG = "WechatAccessbilityJob";

    /** 微信的包名*/
    public static final String WECHAT_PACKAGENAME = "com.tencent.mm";

    /** 红包消息的关键字*/
    private static final String HONGBAO_TEXT_KEY = "[微信红包]";

    private static final String BUTTON_CLASS_NAME = "android.widget.Button";


    /** 不能再使用文字匹配的最小版本号 */
    private static final int USE_ID_MIN_VERSION = 700;// 6.3.8 对应code为680,6.3.9对应code为700

    private static final int WINDOW_NONE = 0;
    private static final int WINDOW_LUCKYMONEY_RECEIVEUI = 1;
    private static final int WINDOW_LUCKYMONEY_DETAIL = 2;
    private static final int WINDOW_LAUNCHER = 3;
    private static final int WINDOW_OTHER = -1;

    private int mCurrentWindow = WINDOW_NONE;

    private boolean isReceivingHongbao;

    private PackageInfo mWechatPackageInfo = null;
    private Handler mHandler = null;

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //更新安装包信息
            updatePackageInfo();
        }
    };

    @Override
    public void onCreateJob(QiangHongBaoService service) {
        super.onCreateJob(service);

        updatePackageInfo();

        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("package");
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REPLACED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");

        getContext().registerReceiver(broadcastReceiver, filter);
    }

    @Override
    public void onStopJob() {
        try {
            getContext().unregisterReceiver(broadcastReceiver);
        } catch (Exception e) {}
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public void onNotificationPosted(IStatusBarNotification sbn) {
        Notification nf = sbn.getNotification();
        String text = String.valueOf(sbn.getNotification().tickerText);
        notificationEvent(text, nf);
    }

    @Override
    public boolean isEnable() {
        return getConfig().isEnableWechat();
    }

    @Override
    public String getTargetPackageName() {
        return WECHAT_PACKAGENAME;
    }

    @Override
    public void onReceiveJob(AccessibilityEvent event) {
        final int eventType = event.getEventType();

        //更新当前页面信息
//        updataCurrentWindowState(event);

        //通知栏事件
        if(eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            Log.d(TAG,"is NOTIFICATION");

            Parcelable data = event.getParcelableData();
            if(data == null || !(data instanceof Notification)) {
                return;
            }
            if(QiangHongBaoService.isNotificationServiceRunning() && getConfig().isEnableNotificationService()) { //开启快速模式，不处理
                return;
            }
            List<CharSequence> texts = event.getText();
            if(!texts.isEmpty()) {
                String text = String.valueOf(texts.get(0));
                notificationEvent(text, (Notification) data);
            }
        } else if(eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG,"is openHongBao");
            openHongBao(event);

        } else if(eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            Log.d(TAG,"is Wachat List mCurrentWindow:"+mCurrentWindow +" isReceivingHongbao:"+isReceivingHongbao);

            if(mCurrentWindow != WINDOW_LAUNCHER) { //不在聊天界面或聊天列表，不处理
                return;
            }

            //检查当前页面
            isReceivingHongbao = checkReceivingHongbao();

            if(isReceivingHongbao) {
                handleChatListHongBao();
            }

        }
    }

    /**
     *  检查当前页面是否有新的红包
     *  判断标准为，最近聊天红包下面没有已经领取字段
     */
    private boolean checkReceivingHongbao() {

        boolean isReceiving = false;

        int mode = getConfig().getWechatMode();
        if(mode == Config.WX_MODE_3) { //只通知模式
            isReceiving = false;
            return isReceiving;
        }

        AccessibilityNodeInfo nodeInfo = getService().getRootInActiveWindow();
        if(nodeInfo == null) {
            Log.w(TAG, "rootWindow为空");
            isReceiving = false;
            return isReceiving;
        }

        if(mode != Config.WX_MODE_0) {
            boolean isMember = isMemberChatUi(nodeInfo);
            if(mode == Config.WX_MODE_1 && isMember) {//过滤群聊
                return isReceiving ;
            } else if(mode == Config.WX_MODE_2 && !isMember) { //过滤单聊
                return isReceiving;
            }
        }

        List<AccessibilityNodeInfo> list = nodeInfo.findAccessibilityNodeInfosByText("领取红包");

        if (list == null | list.isEmpty()){
            return isReceiving;
        }

        //如果存在

        Rect nodeBounds = new Rect();
        Rect alreadBounds = new Rect();


        if (list != null && !list.isEmpty()){

            //获取最后一个
            AccessibilityNodeInfo node = list.get(list.size() - 1);
            node.getBoundsInScreen(nodeBounds);

            List<AccessibilityNodeInfo> alreadyList = nodeInfo.findAccessibilityNodeInfosByText("你领取了");
            if (alreadyList == null || alreadyList.isEmpty()){
                //说明ok，红包存在没有被领取
                isReceiving = true;
            }else {

                //获取最近的一个你领取了关键字的位置，和最新的比较，如果 你领取了，比红包位置纵坐标小，则表示红包存在，否则表示历史红包已经领取
                AccessibilityNodeInfo alreadynodeInfo1 = alreadyList.get(alreadyList.size() - 1);
                alreadynodeInfo1.getBoundsInScreen(alreadBounds);

                if(BuildConfig.DEBUG) {
                    Log.d(TAG, "nodeBounds;" + nodeBounds);
                    Log.d(TAG,"alreadBounds:"+alreadBounds);
                }

                isReceiving =  alreadBounds.bottom < nodeBounds.bottom;
            }

        }

        return isReceiving;



//        if(list != null && list.isEmpty()) {
//            // 从消息列表查找红包
//            AccessibilityNodeInfo node = AccessibilityHelper.findNodeInfosByText(nodeInfo, "[微信红包]");
//            if(node != null) {
//                if(BuildConfig.DEBUG) {
//                    Log.i(TAG, "-->微信红包:" + node);
//                }
//                isReceivingHongbao = true;
//            }
//        } else if(list != null) {
//            if (isReceivingHongbao){
//                //最新的红包领起
//                AccessibilityNodeInfo node = list.get(list.size() - 1);
//                AccessibilityHelper.performClick(node);
//                isReceivingHongbao = false;
//            }
//        }








    }

    /** 是否为群聊天*/
    private boolean isMemberChatUi(AccessibilityNodeInfo nodeInfo) {
        if(nodeInfo == null) {
            return false;
        }
        String id = "com.tencent.mm:id/ces";
        int wv = getWechatVersion();
        if(wv <= 680) {
            id = "com.tencent.mm:id/ew";
        } else if(wv <= 700) {
            id = "com.tencent.mm:id/cbo";
        }
        String title = null;
        AccessibilityNodeInfo target = AccessibilityHelper.findNodeInfosById(nodeInfo, id);
        if(target != null) {
            title = String.valueOf(target.getText());
        }
        List<AccessibilityNodeInfo> list = nodeInfo.findAccessibilityNodeInfosByText("返回");

        if(list != null && !list.isEmpty()) {
            AccessibilityNodeInfo parent = null;
            for(AccessibilityNodeInfo node : list) {
                if(!"android.widget.ImageView".equals(node.getClassName())) {
                    continue;
                }
                String desc = String.valueOf(node.getContentDescription());
                if(!"返回".equals(desc)) {
                    continue;
                }
                parent = node.getParent();
                break;
            }
            if(parent != null) {
                parent = parent.getParent();
            }
            if(parent != null) {
                if( parent.getChildCount() >= 2) {
                    AccessibilityNodeInfo node = parent.getChild(1);
                    if("android.widget.TextView".equals(node.getClassName())) {
                        title = String.valueOf(node.getText());
                    }
                }
            }
        }


        if(title != null && title.endsWith(")")) {
            return true;
        }
        return false;
    }

    /** 通知栏事件*/
    private void notificationEvent(String ticker, Notification nf) {
        String text = ticker;
        int index = text.indexOf(":");
        if(index != -1) {
            text = text.substring(index + 1);
        }
        text = text.trim();
        if(text.contains(HONGBAO_TEXT_KEY)) { //红包消息
            newHongBaoNotification(nf);
        }
    }

    /** 打开通知栏消息*/
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void newHongBaoNotification(Notification notification) {
        isReceivingHongbao = true;
        //以下是精华，将微信的通知栏消息打开
        PendingIntent pendingIntent = notification.contentIntent;
        boolean lock = NotifyHelper.isLockScreen(getContext());

        if(!lock) {
            NotifyHelper.send(pendingIntent);
        } else {
            NotifyHelper.showNotify(getContext(), String.valueOf(notification.tickerText), pendingIntent);
        }

        if(lock || getConfig().getWechatMode() != Config.WX_MODE_0) {
            NotifyHelper.playEffect(getContext(), getConfig());
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void openHongBao(AccessibilityEvent event) {

        if("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI".equals(event.getClassName())) {
            mCurrentWindow = WINDOW_LUCKYMONEY_RECEIVEUI;
            //点中了红包，下一步就是去拆红包
            handleLuckyMoneyReceive();
        } else if("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI".equals(event.getClassName())) {
            mCurrentWindow = WINDOW_LUCKYMONEY_DETAIL;
            //拆完红包后看详细的纪录界面
            if(getConfig().getWechatAfterGetHongBaoEvent() == Config.WX_AFTER_GET_GOHOME) { //返回主界面，以便收到下一次的红包通知
                AccessibilityHelper.performHome(getService());
            }
        } else if("com.tencent.mm.ui.LauncherUI".equals(event.getClassName())) {
            mCurrentWindow = WINDOW_LAUNCHER;
            //在聊天界面,去点中红包
            handleChatListHongBao();
        } else {
            mCurrentWindow = WINDOW_OTHER;
        }
    }


    /**
     * 更新当前页面的页面状态信息
     * 是否是聊天列表还是红包页面
     */
   private void  updataCurrentWindowState(AccessibilityEvent event){


       Log.d(TAG,"ClassName:"+event.getClassName());

       if("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI".equals(event.getClassName())) {
           mCurrentWindow = WINDOW_LUCKYMONEY_RECEIVEUI;
       } else if("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI".equals(event.getClassName())) {
           mCurrentWindow = WINDOW_LUCKYMONEY_DETAIL;
       } else if("com.tencent.mm.ui.LauncherUI".equals(event.getClassName())) {
           mCurrentWindow = WINDOW_LAUNCHER;
       } else {
           mCurrentWindow = WINDOW_OTHER;
       }

    }

    /**
     * 点击聊天里的红包后，显示的界面
     * */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void handleLuckyMoneyReceive() {
        AccessibilityNodeInfo nodeInfo = getService().getRootInActiveWindow();
        if(nodeInfo == null) {
            Log.w(TAG, "rootWindow为空");
            return;
        }

        AccessibilityNodeInfo targetNode = null;

        int event = getConfig().getWechatAfterOpenHongBaoEvent();
        int wechatVersion = getWechatVersion();
        if(event == Config.WX_AFTER_OPEN_HONGBAO) { //拆红包
            if (wechatVersion < USE_ID_MIN_VERSION) {
                targetNode = AccessibilityHelper.findNodeInfosByText(nodeInfo, "拆红包");
            } else {
                String buttonId = "com.tencent.mm:id/b43";

                if(wechatVersion == 700) {
                    buttonId = "com.tencent.mm:id/b2c";
                }

                if(buttonId != null) {
                    targetNode = AccessibilityHelper.findNodeInfosById(nodeInfo, buttonId);
                }

                if(targetNode == null) {
                    //分别对应固定金额的红包 拼手气红包
                    AccessibilityNodeInfo textNode = AccessibilityHelper.findNodeInfosByTexts(nodeInfo, "发了一个红包", "给你发了一个红包", "发了一个红包，金额随机");

                    if(textNode != null) {
                        for (int i = 0; i < textNode.getChildCount(); i++) {
                            AccessibilityNodeInfo node = textNode.getChild(i);
                            if (BUTTON_CLASS_NAME.equals(node.getClassName())) {
                                targetNode = node;
                                break;
                            }
                        }
                    }
                }

                if(targetNode == null) { //通过组件查找
                    targetNode = AccessibilityHelper.findNodeInfosByClassName(nodeInfo, BUTTON_CLASS_NAME);
                }
            }
        } else if(event == Config.WX_AFTER_OPEN_SEE) { //看一看
            if(getWechatVersion() < USE_ID_MIN_VERSION) { //低版本才有 看大家手气的功能
                targetNode = AccessibilityHelper.findNodeInfosByText(nodeInfo, "看看大家的手气");
            }
        } else if(event == Config.WX_AFTER_OPEN_NONE) {
            return;
        }

        if(targetNode != null) {
            final AccessibilityNodeInfo n = targetNode;
            long sDelayTime = getConfig().getWechatOpenDelayTime();
            if(sDelayTime != 0) {
                getHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        AccessibilityHelper.performClick(n);
                    }
                }, sDelayTime);
            } else {
                AccessibilityHelper.performClick(n);
            }
            if(event == Config.WX_AFTER_OPEN_HONGBAO) {
                QHBApplication.eventStatistics(getContext(), "open_hongbao");
            } else {
                QHBApplication.eventStatistics(getContext(), "open_see");
            }
        }
    }

    /**
     * 收到聊天里的红包
     * */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void handleChatListHongBao() {

        int mode = getConfig().getWechatMode();
        if(mode == Config.WX_MODE_3) { //只通知模式
            return;
        }

        AccessibilityNodeInfo nodeInfo = getService().getRootInActiveWindow();
        if(nodeInfo == null) {
            Log.w(TAG, "rootWindow为空");
            return;
        }

        Log.d(TAG,"is handleChatListHongBao"+isReceivingHongbao);

        if(mode != Config.WX_MODE_0) {
            boolean isMember = isMemberChatUi(nodeInfo);
            if(mode == Config.WX_MODE_1 && isMember) {//过滤群聊
                return;
            } else if(mode == Config.WX_MODE_2 && !isMember) { //过滤单聊
                return;
            }
        }


        isReceivingHongbao = checkReceivingHongbao();


        if (isReceivingHongbao){

            List<AccessibilityNodeInfo> list = nodeInfo.findAccessibilityNodeInfosByText("领取红包");

            if(list != null && list.isEmpty()) {
                // 从消息列表查找红包
                AccessibilityNodeInfo node = AccessibilityHelper.findNodeInfosByText(nodeInfo, "[微信红包]");
                if(node != null) {
                    if(BuildConfig.DEBUG) {
                        Log.i(TAG, "-->微信红包:" + node);
                    }

                    isReceivingHongbao = checkReceivingHongbao();

                    AccessibilityHelper.performClick(nodeInfo);
                }
            } else if(list != null) {
                if (isReceivingHongbao){
                    //最新的红包领起
                    AccessibilityNodeInfo node = list.get(list.size() - 1);
                    AccessibilityHelper.performClick(node);
                    isReceivingHongbao = false;
                }
            }
        }

    }

    /**
     * 点击返回键
     */
    private void  back2LuckyMoneyDetailUI(){



    }

    private Handler getHandler() {
        if(mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        return mHandler;
    }

    /** 获取微信的版本*/
    private int getWechatVersion() {
        if(mWechatPackageInfo == null) {
            return 0;
        }
        return mWechatPackageInfo.versionCode;
    }

    /** 更新微信包信息*/
    private void updatePackageInfo() {
        try {
            mWechatPackageInfo = getContext().getPackageManager().getPackageInfo(WECHAT_PACKAGENAME, 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }
}
