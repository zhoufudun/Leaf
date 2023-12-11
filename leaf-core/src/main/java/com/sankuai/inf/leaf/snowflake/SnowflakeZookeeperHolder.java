package com.sankuai.inf.leaf.snowflake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sankuai.inf.leaf.snowflake.exception.CheckLastTimeException;
import org.apache.commons.io.FileUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.retry.RetryUntilElapsed;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.sankuai.inf.leaf.common.*;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.zookeeper.CreateMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class SnowflakeZookeeperHolder {
    private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeZookeeperHolder.class);
    private String zk_AddressNode = null;//保存自身的key  ip:port-000000001
    private String listenAddress = null;//保存自身的key ip:port
    private int workerID;
    private static final String PREFIX_ZK_PATH = "/snowflake/" + PropertyFactory.getProperties().getProperty("leaf.name");
    // C:\Users\Administrator\AppData\Local\Temp\\com.sankuai.leaf.opensource.test/leafconf/{port}/workerID.properties
    private static final String PROP_PATH = System.getProperty("java.io.tmpdir") + File.separator + PropertyFactory.getProperties().getProperty("leaf.name") + "/leafconf/{port}/workerID.properties";
    private static final String PATH_FOREVER = PREFIX_ZK_PATH + "/forever";//保存所有数据持久的节点
    private String ip;
    private String port;
    private String connectionString;
    private long lastUpdateTime;

    public SnowflakeZookeeperHolder(String ip, String port, String connectionString) {
        this.ip = ip; // 10.2.40.18
        this.port = port; // 9987
        this.listenAddress = ip + ":" + port; // 127.0.0.1:2181
        this.connectionString = connectionString; // 127.0.0.1:2181
    }

    public boolean init() {
        try {
            CuratorFramework curator = createWithOptions(connectionString, new RetryUntilElapsed(1000, 4), 10000, 6000);
            curator.start();
            Stat stat = curator.checkExists().forPath(PATH_FOREVER);
            if (stat == null) {
                //不存在根节点,机器第一次启动,创建/snowflake/ip:port-000000000,并上传数据
                zk_AddressNode = createNode(curator); // /snowflake/com.sankuai.leaf.opensource.test/forever/10.2.40.18:9987-0000000000
                //worker id 默认是0
                updateLocalWorkerID(workerID);
                //定时上报本机时间给forever节点
                ScheduledUploadData(curator, zk_AddressNode);
                return true;
            } else {
                Map<String, Integer> nodeMap = Maps.newHashMap();//ip:port->00001(workId)
                Map<String, String> realNode = Maps.newHashMap();//ip:port->(ip:port-000001)
                //存在根节点,先检查是否有属于自己的根节点
                List<String> keys = curator.getChildren().forPath(PATH_FOREVER); // 10.2.40.18:9987-0000000000
                for (String key : keys) {
                    String[] nodeKey = key.split("-");
                    realNode.put(nodeKey[0], key);
                    nodeMap.put(nodeKey[0], Integer.parseInt(nodeKey[1]));
                }
                Integer workerid = nodeMap.get(listenAddress);
                if (workerid != null) {
                    //有自己的节点,zk_AddressNode=ip:port
                    zk_AddressNode = PATH_FOREVER + "/" + realNode.get(listenAddress); // /snowflake/com.sankuai.leaf.opensource.test/forever/10.2.40.18:9987-0000000000
                    workerID = workerid;//启动worder时使用会使用
                    if (!checkInitTimeStamp(curator, zk_AddressNode)) {
                        throw new CheckLastTimeException("init timestamp check error,forever node timestamp gt this node time");
                    }
                    //准备创建临时节点
                    doService(curator);
                    updateLocalWorkerID(workerID);
                    LOGGER.info("[Old NODE]find forever node have this endpoint ip-{} port-{} workid-{} childnode and start SUCCESS", ip, port, workerID);
                } else {
                    //表示新启动的节点,创建持久节点 ,不用check时间
                    String newNode = createNode(curator);
                    zk_AddressNode = newNode;
                    String[] nodeKey = newNode.split("-");
                    workerID = Integer.parseInt(nodeKey[1]);
                    doService(curator);
                    updateLocalWorkerID(workerID);
                    LOGGER.info("[New NODE]can not find node on forever node that endpoint ip-{} port-{} workid-{},create own node on forever node and start SUCCESS ", ip, port, workerID);
                }
            }
        } catch (Exception e) { // zk挂了, 机器刚好启动
            LOGGER.error("Start node ERROR {}", e);
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(new File(PROP_PATH.replace("{port}", port + ""))));
                workerID = Integer.valueOf(properties.getProperty("workerID"));
                LOGGER.warn("START FAILED ,use local node file properties workerID-{}", workerID);
            } catch (Exception e1) {
                LOGGER.error("Read file error ", e1);
                return false;
            }
        }
        return true;
    }

    private void doService(CuratorFramework curator) {
        ScheduledUploadData(curator, zk_AddressNode);// /snowflake_forever/ip:port-000000001
    }

    private void ScheduledUploadData(final CuratorFramework curator, final String zk_AddressNode) {
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "schedule-upload-time");
                thread.setDaemon(true);
                return thread;
            }
        }).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                updateNewData(curator, zk_AddressNode);
            }
        }, 1L, 3L, TimeUnit.SECONDS);//每3s上报数据

    }

    private boolean checkInitTimeStamp(CuratorFramework curator, String zk_AddressNode) throws Exception {
        byte[] bytes = curator.getData().forPath(zk_AddressNode);
        Endpoint endPoint = deBuildData(new String(bytes));
        //该节点的时间不能小于最后一次上报的时间
        return !(endPoint.getTimestamp() > System.currentTimeMillis());
    }

    /**
     * 创建持久顺序节点 ,并把节点数据放入 value
     *
     * @param curator
     * @return
     * @throws Exception
     */
    private String createNode(CuratorFramework curator) throws Exception {
        try {
            return curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(PATH_FOREVER + "/" + listenAddress + "-", buildData().getBytes());
        } catch (Exception e) {
            LOGGER.error("create node error msg {} ", e.getMessage());
            throw e;
        }
    }

    private void updateNewData(CuratorFramework curator, String path) { // path=/snowflake/com.sankuai.leaf.opensource.test/forever/10.2.40.18:9987-0000000000
        try {
            if (System.currentTimeMillis() < lastUpdateTime) {
                return;
            }
            curator.setData().forPath(path, buildData().getBytes());
            lastUpdateTime = System.currentTimeMillis();
        } catch (Exception e) {
            LOGGER.info("update init data error path is {} error is {}", path, e);
        }
    }

    /**
     * 构建需要上传的数据
     *
     * @return
     */
    private String buildData() throws JsonProcessingException {
        Endpoint endpoint = new Endpoint(ip, port, System.currentTimeMillis());
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(endpoint);
        return json; // {"ip":"10.2.40.18","port":"9987","timestamp":1702296073133}
    }

    private Endpoint deBuildData(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Endpoint endpoint = mapper.readValue(json, Endpoint.class);
        return endpoint; // {"ip":"10.2.40.18","port":"9987","timestamp":1702296224726}
    }

    /**
     * 在节点文件系统上缓存一个workid值,zk失效,机器重启时保证能够正常启动
     *
     * @param workerID
     */
    private void updateLocalWorkerID(int workerID) {
        File leafConfFile = new File(PROP_PATH.replace("{port}", port)); // C:\Users\Administrator\AppData\Local\Temp\com.sankuai.leaf.opensource.test\leafconf\9987\workerID.properties
        boolean exists = leafConfFile.exists();
        LOGGER.info("file exists status is {}", exists);
        if (exists) {
            try {
                FileUtils.writeStringToFile(leafConfFile, "workerID=" + workerID, false);
                LOGGER.info("update file cache workerID is {}", workerID);
            } catch (IOException e) {
                LOGGER.error("update file cache error ", e);
            }
        } else {
            //不存在文件,父目录页肯定不存在
            try {
                boolean mkdirs = leafConfFile.getParentFile().mkdirs();
                LOGGER.info("init local file cache create parent dis status is {}, worker id is {}", mkdirs, workerID);
                if (mkdirs) {
                    if (leafConfFile.createNewFile()) {
                        FileUtils.writeStringToFile(leafConfFile, "workerID=" + workerID, false);
                        LOGGER.info("local file cache workerID is {}", workerID);
                    }
                } else {
                    LOGGER.warn("create parent dir error===");
                }
            } catch (IOException e) {
                LOGGER.warn("craete workerID conf file error", e);
            }
        }
    }

    private CuratorFramework createWithOptions(String connectionString, RetryPolicy retryPolicy, int connectionTimeoutMs, int sessionTimeoutMs) {
        return CuratorFrameworkFactory.builder().connectString(connectionString)
                .retryPolicy(retryPolicy)
                .connectionTimeoutMs(connectionTimeoutMs)
                .sessionTimeoutMs(sessionTimeoutMs)
                .build();
    }

    /**
     * 上报数据结构
     */
    static class Endpoint {
        private String ip;
        private String port;
        private long timestamp;

        public Endpoint() {
        }

        public Endpoint(String ip, String port, long timestamp) {
            this.ip = ip;
            this.port = port;
            this.timestamp = timestamp;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    public String getZk_AddressNode() {
        return zk_AddressNode;
    }

    public void setZk_AddressNode(String zk_AddressNode) {
        this.zk_AddressNode = zk_AddressNode;
    }

    public String getListenAddress() {
        return listenAddress;
    }

    public void setListenAddress(String listenAddress) {
        this.listenAddress = listenAddress;
    }

    public int getWorkerID() {
        return workerID;
    }

    public void setWorkerID(int workerID) {
        this.workerID = workerID;
    }

}
