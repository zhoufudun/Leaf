package com.sankuai.inf.leaf.snowflake;

import com.google.common.base.Preconditions;
import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;
import com.sankuai.inf.leaf.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class SnowflakeIDGenImpl implements IDGen {

    @Override
    public boolean init() {
        return true;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeIDGenImpl.class);

    // 雪花分段
    // 1（固定=0）+41（时间戳ms，最大69年）+10（5[机房id]+5[机器id]）+12（序列号[最大4095]）
    private final long twepoch;// 41bit的时间戳（单位ms）
    private final long workerIdBits = 10L; // 10bit的机器id
    private final long maxWorkerId = ~(-1L << workerIdBits);//最大能够分配的workerid =1023 (2^10-1)
    private final long sequenceBits = 12L; // 12比特的序列号
    private final long workerIdShift = sequenceBits;
    private final long timestampLeftShift = sequenceBits + workerIdBits;
    private final long sequenceMask = ~(-1L << sequenceBits);// 最大序列号4095（12bit）
    private long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private static final Random RANDOM = new Random();

    public SnowflakeIDGenImpl(String zkAddress, int port) {
        //Thu Nov 04 2010 09:42:54 GMT+0800 (中国标准时间) 
        this(zkAddress, port, 1288834974657L);
    }

    /**
     * @param zkAddress zk地址
     * @param port      snowflake监听端口
     * @param twepoch   起始的时间戳
     */
    public SnowflakeIDGenImpl(String zkAddress, int port, long twepoch) {
        this.twepoch = twepoch;
        Preconditions.checkArgument(timeGen() > twepoch, "Snowflake not support twepoch gt currentTime");
        final String ip = Utils.getIp();
        SnowflakeZookeeperHolder holder = new SnowflakeZookeeperHolder(ip, String.valueOf(port), zkAddress);
        LOGGER.info("twepoch:{} ,ip:{} ,zkAddress:{} port:{}", twepoch, ip, zkAddress, port);
        boolean initFlag = holder.init();
        if (initFlag) {
            workerId = holder.getWorkerID();
            LOGGER.info("START SUCCESS USE ZK WORKERID-{}", workerId);
        } else {
            Preconditions.checkArgument(initFlag, "Snowflake Id Gen is not init ok");
        }
        Preconditions.checkArgument(workerId >= 0 && workerId <= maxWorkerId, "workerID must gte 0 and lte 1023");
    }

    @Override
    public synchronized Result get(String key) {
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) { // 如果发生时钟回拨了，回拨时间<5ms
                try {
                    wait(offset << 1); // 等待时间=offset*2
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        return new Result(-1, Status.EXCEPTION); // 等待一段时间还是时间回拨，返回异常
                    }
                } catch (InterruptedException e) {
                    LOGGER.error("wait interrupted");
                    return new Result(-2, Status.EXCEPTION);
                }
            } else {
                return new Result(-3, Status.EXCEPTION);
            }
        }
        if (lastTimestamp == timestamp) { // 同1ms内，多个请求
            sequence = (sequence + 1) & sequenceMask; // 序列号+1 和 4095按位与
            if (sequence == 0) { // sequence+1 > 4095
                //seq 为0的时候表示是下一毫秒时间开始对seq做随机
                sequence = RANDOM.nextInt(100);
                timestamp = tilNextMillis(lastTimestamp); // 等待到下一个模ms
            }
        } else {
            //如果是新的1ms开始
            sequence = RANDOM.nextInt(100); // 新的1ms内，sequence初始值使用一个随机数
        }
        lastTimestamp = timestamp;
        // 1+41+10+12 的到一个id
        long id = ((timestamp - twepoch) << timestampLeftShift) | (workerId << workerIdShift) | sequence;
        return new Result(id, Status.SUCCESS);

    }

    /**
     * 等待到下一ms
     * @param lastTimestamp
     */
    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    protected long timeGen() {
        return System.currentTimeMillis();
    }

    public long getWorkerId() {
        return workerId;
    }

}
