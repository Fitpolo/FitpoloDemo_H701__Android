package com.fitpolo.support.task;

import com.fitpolo.support.MokoSupport;
import com.fitpolo.support.callback.MokoOrderTaskCallback;
import com.fitpolo.support.entity.DailySleep;
import com.fitpolo.support.entity.OrderEnum;
import com.fitpolo.support.entity.OrderType;
import com.fitpolo.support.log.LogModule;
import com.fitpolo.support.utils.ComplexDataParse;
import com.fitpolo.support.utils.DigitalConver;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

/**
 * @Date 2017/5/11
 * @Author wenzheng.liu
 * @Description 获取未同步的睡眠记录数据
 * @ClassPath com.fitpolo.support.task.LastestSleepIndexTask
 */
public class LastestSleepIndexTask extends OrderTask {

    private static final int ORDERDATA_LENGTH = 7;
    // 获取最新数据
    private static final int HEADER_GET_NEW_DATA = 0x2C;
    // 返回睡眠index数据头
    private static final int RESPONSE_HEADER_SLEEP_INDEX = 0x93;
    // 返回未同步数据头
    private static final int RESPONSE_HEADER_NEW_DATA_COUNT = 0xAA;
    // 返回睡眠record数据头
    private static final int RESPONSE_HEADER_SLEEP_RECORD = 0x94;

    private byte[] orderData;

    private HashMap<Integer, DailySleep> sleepsMap;
    private ArrayList<DailySleep> dailySleeps;
    private int sleepIndexCount;
    private int sleepRecordCount;

    private Calendar lastSyncTime;// yyyy-MM-dd HH:mm

    public LastestSleepIndexTask(MokoOrderTaskCallback callback, Calendar lastSyncTime) {
        super(OrderType.WRITE, OrderEnum.getLastestSleepIndex, callback, OrderTask.RESPONSE_TYPE_WRITE_NO_RESPONSE);
        this.lastSyncTime = lastSyncTime;
        isNewDataSuccess = false;
        orderData = new byte[ORDERDATA_LENGTH];
        int year = lastSyncTime.get(Calendar.YEAR) - 2000;
        int month = lastSyncTime.get(Calendar.MONTH) + 1;
        int day = lastSyncTime.get(Calendar.DAY_OF_MONTH);

        int hour = lastSyncTime.get(Calendar.HOUR_OF_DAY);
        int minute = lastSyncTime.get(Calendar.MINUTE);

        orderData[0] = (byte) HEADER_GET_NEW_DATA;
        orderData[1] = (byte) year;
        orderData[2] = (byte) month;
        orderData[3] = (byte) day;
        orderData[4] = (byte) hour;
        orderData[5] = (byte) minute;
        orderData[6] = (byte) RESPONSE_HEADER_SLEEP_INDEX;
    }

    @Override
    public byte[] assemble() {
        return orderData;
    }

    @Override
    public void parseValue(byte[] value) {
        if (order.getOrderHeader() != DigitalConver.byte2Int(value[0]) && order.getOrderHeader() != DigitalConver.byte2Int(value[1])) {
            return;
        }
        LogModule.i(order.getOrderName() + "成功");
        // 获取睡眠总数标记成功
        isNewDataSuccess = true;
        int header = DigitalConver.byte2Int(value[0]);
        switch (header) {
            case RESPONSE_HEADER_NEW_DATA_COUNT:
                byte[] count = new byte[value.length - 2];
                System.arraycopy(value, 2, count, 0, value.length - 2);
                sleepIndexCount = DigitalConver.byteArr2Int(count);
                MokoSupport.getInstance().setSleepIndexCount(sleepIndexCount);
                MokoSupport.getInstance().setSleepRecordCount(sleepIndexCount * 2);
                LogModule.i("有" + sleepIndexCount + "条睡眠index");
                MokoSupport.getInstance().initSleepIndexList();
                sleepsMap = MokoSupport.getInstance().getSleepsMap();
                dailySleeps = MokoSupport.getInstance().getDailySleeps();
                delayTime = sleepIndexCount == 0 ? DEFAULT_DELAY_TIME : DEFAULT_DELAY_TIME + 100 * (sleepIndexCount + sleepIndexCount * 2);
                // 拿到条数后再启动超时任务
                MokoSupport.getInstance().timeoutHandler(this);
                break;
            case RESPONSE_HEADER_SLEEP_INDEX:
                if (sleepIndexCount > 0) {
                    if (dailySleeps == null) {
                        dailySleeps = new ArrayList<>();
                    }
                    if (sleepsMap == null) {
                        sleepsMap = new HashMap<>();
                    }
                    dailySleeps.add(ComplexDataParse.parseDailySleepIndex(value, sleepsMap, 1));
                    sleepIndexCount--;

                    MokoSupport.getInstance().setDailySleeps(dailySleeps);
                    MokoSupport.getInstance().setSleepIndexCount(sleepIndexCount);
                    MokoSupport.getInstance().setSleepsMap(sleepsMap);
                    if (sleepIndexCount > 0) {
                        LogModule.i("还有" + sleepIndexCount + "条睡眠index数据未同步");
                        return;
                    }
                }
                break;
            default:
                return;
        }
        if (!dailySleeps.isEmpty()) {
            // 请求完index后请求record
            LastestSleepRecordTask lastestSleepRecordTask = new LastestSleepRecordTask(callback, lastSyncTime);
            MokoSupport.getInstance().sendCustomOrder(lastestSleepRecordTask);
        } else {
            if (sleepIndexCount != 0) {
                return;
            }
            MokoSupport.getInstance().setSleepIndexCount(sleepIndexCount);
            MokoSupport.getInstance().setDailySleeps(dailySleeps);
            MokoSupport.getInstance().setSleepsMap(sleepsMap);
            orderStatus = OrderTask.ORDER_STATUS_SUCCESS;
            MokoSupport.getInstance().pollTask();
            callback.onOrderResult(response);
            MokoSupport.getInstance().executeTask(callback);
        }

    }

    public void parseRecordValue(byte[] value) {
        if (RESPONSE_HEADER_SLEEP_RECORD != (value[0] & 0xFF) && RESPONSE_HEADER_SLEEP_RECORD != (value[1] & 0xFF)) {
            return;
        }
        LogModule.i("获取未同步的睡眠详情数据成功");
        int header = DigitalConver.byte2Int(value[0]);
        if (header == RESPONSE_HEADER_NEW_DATA_COUNT) {
            byte[] count = new byte[value.length - 2];
            System.arraycopy(value, 2, count, 0, value.length - 2);
            sleepRecordCount = DigitalConver.byteArr2Int(count);
            LogModule.i("有" + sleepRecordCount + "条睡眠record");
            MokoSupport.getInstance().setSleepRecordCount(sleepRecordCount);
        }
        if (header == RESPONSE_HEADER_SLEEP_RECORD) {
            if (sleepRecordCount > 0) {
                ComplexDataParse.parseDailySleepRecord(value, sleepsMap, 1);
                sleepRecordCount--;
                MokoSupport.getInstance().setSleepRecordCount(sleepRecordCount);
                if (sleepRecordCount > 0) {
                    LogModule.i("还有" + sleepRecordCount + "条睡眠record数据未同步");
                    return;
                }
            }
        }
        if (sleepRecordCount != 0) {
            return;
        }
        MokoSupport.getInstance().setSleepRecordCount(sleepRecordCount);
        MokoSupport.getInstance().setDailySleeps(dailySleeps);
        sleepsMap.clear();
        MokoSupport.getInstance().setSleepsMap(sleepsMap);
        orderStatus = OrderTask.ORDER_STATUS_SUCCESS;
        MokoSupport.getInstance().pollTask();
        callback.onOrderResult(response);
        MokoSupport.getInstance().executeTask(callback);
    }

    private boolean isNewDataSuccess;
    private boolean isReceiveDetail;

    @Override
    public boolean timeoutPreTask() {
        if (!isReceiveDetail) {
            if (!isNewDataSuccess) {
                LogModule.i("获取未同步的睡眠记录个数超时");
            } else {
                isReceiveDetail = true;
                return false;
            }
        }
        if (sleepsMap != null) {
            sleepsMap.clear();
            MokoSupport.getInstance().setSleepsMap(sleepsMap);
        }
        return super.timeoutPreTask();
    }
}
