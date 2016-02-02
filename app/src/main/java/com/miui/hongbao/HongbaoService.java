package com.miui.hongbao;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.os.Parcelable;
import android.os.PowerManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class HongbaoService extends AccessibilityService {

    private final static String TAG = "GOTCHA";

    /**
     * 已抢过的红包
     */
    private List<String> finishedNode = new ArrayList<>();

    private AccessibilityNodeInfo rootNodeInfo;
    /**
     * 当前处理的红包节点标志字符串
     */
    private String currentNodeInfo;

    public final static String WECHAT_DETAILS_EN = "Details";
    public final static String WECHAT_DETAILS_CH = "红包详情";
    public final static String WECHAT_BETTER_LUCK_EN = "Better luck next time!";
    public final static String WECHAT_BETTER_LUCK_CH = "手慢了";
    public final static String WECHAT_OPENED_EN = "opened";
    public final static String WECHAT_VIEW_SELF_CH = "查看红包";
    public final static String WECHAT_VIEW_OTHERS_CH = "领取红包";
    public final static String NOTIFICATION_TIP = "[微信红包]";
    public final static String NOTIFICATION_QQ = "[QQ红包]";


    /**
     * 用来鉴别对象是否为微信红包的字符串
     */
    private final static String VERIFY_TEXT = "微信红包";


    private PowerManager pm;

    private PowerManager.WakeLock lock;

    private KeyguardManager kManager;

    private KeyguardManager.KeyguardLock kLock;

    private HongbaoState hongbao;

    public void setHongbao(HongbaoState hongbao) {
        this.hongbao = hongbao;
    }

    /**
     * 初始化环境
     */
    private void prepare() {
        if (pm == null) {
            pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        }

        if (kManager == null) {
            kManager = ((KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE));
        }
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        lock.acquire();

        kLock = kManager.newKeyguardLock(TAG);
        kLock.disableKeyguard();
    }

    /**
     * 清理环境
     */
    private void clean() {
        if (lock != null) {
            lock.release();
            lock = null;
        }

        if (kLock != null) {
            kLock.reenableKeyguard();
            kLock = null;
        }
    }


    /**
     * AccessibilityEvent的回调方法
     *
     * @param event 事件
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {


        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            Log.d(TAG, event.getText().toString());
            String tip = event.getText().toString();
            if (!tip.contains(NOTIFICATION_TIP) && !tip.contains(NOTIFICATION_QQ)) {
                return;
            }
            Parcelable parcelable = event.getParcelableData();
            if (parcelable instanceof Notification) {
                Notification notification = (Notification) parcelable;
                Log.d(TAG, "Notification: " + notification.contentIntent.toString());
                try {
                    prepare();
                    ((Notification) parcelable).contentIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "", e);
                }
            }
            return;
        }


        rootNodeInfo = event.getSource();

        if (rootNodeInfo == null) return;

        this.hongbao = checkNodeInfo();

        if (this.hongbao == null) {
            return;
        }


        this.hongbao.performAction();
        clean();
    }

    @Override
    public void onInterrupt() {

    }

    /**
     * 检查节点信息
     *
     * @return 返回当前屏幕决定的红包状态
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private HongbaoState checkNodeInfo() {
        if (this.rootNodeInfo == null) return null;

        /* 聊天会话窗口，遍历节点匹配“领取红包”和"查看红包" */
        List<AccessibilityNodeInfo> nodesWaiting = findAccessibilityNodeInfosByTexts(this.rootNodeInfo, new String[]{
                this.WECHAT_VIEW_SELF_CH, this.WECHAT_VIEW_OTHERS_CH});

        if (!nodesWaiting.isEmpty()) {

            Log.v(TAG, "Got " + nodesWaiting.size() + " nodes");

            for (AccessibilityNodeInfo node : nodesWaiting) {

                if (!isHongbaoObj(node)) {
                    Log.d(TAG, "Not Real Node, bypass");
                    continue;
                }

                if (finishedNode.contains(getSignature(node))) {
                    Log.v(TAG, "Already opened, bypass");
                    continue;
                }
                currentNodeInfo = getSignature(node);
                return new HongbaoUnopened(this, node);

            }
        }

        /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
        AccessibilityNodeInfo unpackedNode = (this.rootNodeInfo.getChildCount() > 3) ? this.rootNodeInfo.getChild(3) : null;

        if (unpackedNode != null && unpackedNode.getClassName().equals("android.widget.Button")) {
            return new HongbaoOpened(this, unpackedNode);
        }


        /* 戳开红包，红包已被抢完，遍历节点匹配“红包详情”和“手慢了” */

        List<AccessibilityNodeInfo> nodes3 = this.findAccessibilityNodeInfosByTexts(this.rootNodeInfo, new String[]{
                this.WECHAT_BETTER_LUCK_CH, this.WECHAT_DETAILS_CH,
                this.WECHAT_BETTER_LUCK_EN, this.WECHAT_DETAILS_EN});

        if (!this.rootNodeInfo.getClassName().equals("android.widget.FrameLayout") || nodes3.size() > 1) { //聊天中出现的字符串不应该做为返回指示
            return null;
        }

        if (!nodes3.isEmpty()) {
            if (!finishedNode.contains(currentNodeInfo)) {
                finishedNode.add(currentNodeInfo);
            }
            return new HongbaoPicked(this);
        }


        return null;
    }

    /**
     * 将节点对象的id和红包上的内容合并
     * 用于表示一个唯一的红包
     *
     * @param node 红包文字对象
     * @return 红包标识字符串(红包文本+Hash+接收时间)
     */
    private String getSignature(AccessibilityNodeInfo node) {

        if (!isHongbaoObj(node)) {
            return null;
        }

        /* 获取红包上的文本 */
        String result = "";
        try {
            // 用正则表达式匹配节点Object
            Pattern objHashPattern = Pattern.compile("(?<=@)[0-9|a-z]+(?=;)");
            Matcher objHashMatcher = objHashPattern.matcher(node.toString());

            // AccessibilityNodeInfo必然有且只有一次匹配，因此不再作判断
            objHashMatcher.find();
            result += objHashMatcher.group(0);

            result += node.getParent().getChild(0).getText().toString(); //文本

            result += node.getParent().getParent().getChild(0).getText().toString(); //或许有时间
        } catch (NullPointerException npe) {
            Log.w(TAG, "Target Without time");
        }

        Log.v(TAG, result);

        return result;
    }

    /**
     * 判断是否为红包Node：其父ViewGroup包含三个元素,且第三个元素为TextView：微信红包
     *
     * @param node
     * @return
     */
    private boolean isHongbaoObj(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        boolean result = false;
        try {
            result = parent.getChildCount() == 3 &&
                    parent.getChild(2).getClassName().toString().contains("TextView") &&
                    parent.getChild(2).getText().toString().equals(VERIFY_TEXT);
        } catch (NullPointerException e) {
            Log.e(TAG, "", e);
        }

        return result;
    }


    /**
     * 批量化执行AccessibilityNodeInfo.findAccessibilityNodeInfosByText(text).
     * 由于这个操作影响性能,将所有需要匹配的文字一起处理,尽早返回
     *
     * @param nodeInfo 窗口根节点
     * @param texts    需要匹配的字符串们
     * @return 匹配到的节点数组
     */
    private List<AccessibilityNodeInfo> findAccessibilityNodeInfosByTexts(AccessibilityNodeInfo nodeInfo, String[] texts) {

        List<AccessibilityNodeInfo> result = new ArrayList<>();

        for (String text : texts) {
            List<AccessibilityNodeInfo> nodes = nodeInfo.findAccessibilityNodeInfosByText(text);

            if (!nodes.isEmpty()) {
                Log.v(TAG, "Current Node with " + text + " is " + nodeInfo.getClassName());
                result.addAll(nodes);
            }
        }
        return result;
    }

}
