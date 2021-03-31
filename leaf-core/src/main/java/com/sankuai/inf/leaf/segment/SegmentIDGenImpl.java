package com.sankuai.inf.leaf.segment;

import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;
import com.sankuai.inf.leaf.segment.dao.IDAllocDao;
import com.sankuai.inf.leaf.segment.model.*;
import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class SegmentIDGenImpl implements IDGen {
    private static final Logger logger = LoggerFactory.getLogger(SegmentIDGenImpl.class);

    /**
     * IDCache未初始化成功时的异常码
     */
    private static final long EXCEPTION_ID_IDCACHE_INIT_FALSE = -1;
    /**
     * key不存在时的异常码
     */
    private static final long EXCEPTION_ID_KEY_NOT_EXISTS = -2;
    /**
     * SegmentBuffer中的两个Segment均未从DB中装载时的异常码
     */
    private static final long EXCEPTION_ID_TWO_SEGMENTS_ARE_NULL = -3;
    /**
     * 最大步长不超过100,0000
     */
    private static final int MAX_STEP = 1000000;
    /**
     * 一个Segment维持时间为15分钟
     */
    private static final long SEGMENT_DURATION = 15 * 60 * 1000L;
    private ExecutorService service = new ThreadPoolExecutor(5, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new UpdateThreadFactory());
    private volatile boolean initOK = false;
    private Map<String, SegmentBuffer> cache = new ConcurrentHashMap<String, SegmentBuffer>();
    private IDAllocDao dao;

    public static class UpdateThreadFactory implements ThreadFactory {

        private static int threadInitNumber = 0;

        private static synchronized int nextThreadNum() {
            return threadInitNumber++;
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "Thread-Segment-Update-" + nextThreadNum());
        }
    }

    @Override
    public boolean init() {
        logger.info("Init ...");
        // 确保加载到kv后才初始化成功
        updateCacheFromDb();
        initOK = true;
        // 开启定时任务，每分钟从DB中同步一次数据，保证DB中新增、删除的数据能及时同步到缓存中
        updateCacheFromDbAtEveryMinute();
        return initOK;
    }

    private void updateCacheFromDbAtEveryMinute() {
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("check-idCache-thread");
                t.setDaemon(true);
                return t;
            }
        });
        service.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                updateCacheFromDb();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 根据DB中的bizTag，同步本地缓存中的数据，保证缓存中bizTag是全量数据，同时会清理已经下线的bizTag
     */
    private void updateCacheFromDb() {
        logger.info("update cache from db");
        StopWatch sw = new Slf4JStopWatch();
        try {
            List<String> dbTags = dao.getAllTags();
            if (dbTags == null || dbTags.isEmpty()) {
                return;
            }
            // 当前jvm本地缓存的所有bizTag，系统第一次启动执行时，缓存为空，以后是有值的
            List<String> cacheTags = new ArrayList<String>(cache.keySet());
            /**
             * 从DB中查询出的所有bizTag，有三种情况
             * 1. DB中数据bizTag数量和缓存中数量一致
             * 2. DB中bizTag多(新接入某些业务的情况)，则需要将缓存中不存在的bizTag初始化对应的号段，并放入缓存
             * 3. DB中bizTag少(某些业务下线的情况)，则需要将缓存中多余的bizTag清除，节省内存
             */
            Set<String> insertTagsSet = new HashSet<>(dbTags);
            // 某些bizTag下线，DB中不存在，需要从缓存中删除的bizTag列表
            Set<String> removeTagsSet = new HashSet<>(cacheTags);
            //db中新加的tags灌进cache
            for(int i = 0; i < cacheTags.size(); i++){
                String tmp = cacheTags.get(i);
                if(insertTagsSet.contains(tmp)){
                    /**
                     * 遍历缓存中存在的bizTag，如果改bizTag也存在于DB中，说明该bizTag还有效，不用重新初始化
                     * 将该bizTag从insertTagsSet中删除，最后剩余的bizTag是DB中新增的且缓存中没有的
                     */
                    insertTagsSet.remove(tmp);
                }
            }
            for (String tag : insertTagsSet) {
                // insertTagsSet中的bizTag是缓存中没有的，需要初始化号段，并加入缓存
                SegmentBuffer buffer = new SegmentBuffer();
                buffer.setKey(tag);
                Segment segment = buffer.getCurrent();
                segment.setValue(new AtomicLong(0));
                segment.setMax(0);
                segment.setStep(0);
                cache.put(tag, buffer);
                logger.info("Add tag {} from db to IdCache, SegmentBuffer {}", tag, buffer);
            }
            //cache中已失效的tags从cache删除
            for(int i = 0; i < dbTags.size(); i++){
                String tmp = dbTags.get(i);
                if(removeTagsSet.contains(tmp)){
                    // 遍历DB中的bizTag，并从集合中删除，最后剩下的就是DB中没有，缓存中存在的，这部分需要清除
                    removeTagsSet.remove(tmp);
                }
            }
            for (String tag : removeTagsSet) {
                // 从缓存中清除DB中不存在的bizTag，节省内存
                cache.remove(tag);
                logger.info("Remove tag {} from IdCache", tag);
            }
        } catch (Exception e) {
            logger.warn("update cache from db exception", e);
        } finally {
            sw.stop("updateCacheFromDb");
        }
    }

    @Override
    public Result get(final String key) {
        if (!initOK) {
            return new Result(EXCEPTION_ID_IDCACHE_INIT_FALSE, Status.EXCEPTION);
        }
        if (cache.containsKey(key)) {
            // 从缓存中获取到对应的号段
            SegmentBuffer buffer = cache.get(key);
            if (!buffer.isInitOk()) {
                // 如果改号段还没有初始化(从DB中加载出来，只是实例化了对象，并没有对对象做初始化)，则加锁并进行初始化
                synchronized (buffer) {
                    // double-check
                    if (!buffer.isInitOk()) {
                        try {
                            updateSegmentFromDb(key, buffer.getCurrent());
                            logger.info("Init buffer. Update leafkey {} {} from db", key, buffer.getCurrent());
                            buffer.setInitOk(true);
                        } catch (Exception e) {
                            logger.warn("Init buffer {} exception", buffer.getCurrent(), e);
                        }
                    }
                }
            }
            // 如果号段已经初始化完成，
            return getIdFromSegmentBuffer(cache.get(key));
        }
        return new Result(EXCEPTION_ID_KEY_NOT_EXISTS, Status.EXCEPTION);
    }

    public void updateSegmentFromDb(String key, Segment segment) {
        StopWatch sw = new Slf4JStopWatch();
        SegmentBuffer buffer = segment.getBuffer();
        LeafAlloc leafAlloc;
        if (!buffer.isInitOk()) {
            // 号段没有初始化，则更新数据库中bizTag的最大值，并返回更新后的值
            leafAlloc = dao.updateMaxIdAndGetLeafAlloc(key);
            // 步长是DB中的步长
            buffer.setStep(leafAlloc.getStep());
            // 最小步长也是DB中的步长
            buffer.setMinStep(leafAlloc.getStep());//leafAlloc中的step为DB中的step
        } else if (buffer.getUpdateTimestamp() == 0) {
            // 更新DB中最大值，并设置号段
            leafAlloc = dao.updateMaxIdAndGetLeafAlloc(key);
            buffer.setUpdateTimestamp(System.currentTimeMillis());
            buffer.setStep(leafAlloc.getStep());
            buffer.setMinStep(leafAlloc.getStep());//leafAlloc中的step为DB中的step
        } else {
            // 如果号段已经初始化完成
            long duration = System.currentTimeMillis() - buffer.getUpdateTimestamp();
            // 动态调整下一次取号段的步长
            int nextStep = buffer.getStep();
            if (duration < SEGMENT_DURATION) {
                // 号段使用时间小于15分钟，如果当前步长的2倍大于最大步长，则不调整，否则，则将步长扩大两倍
                if (nextStep * 2 > MAX_STEP) {
                    //do nothing
                } else {
                    nextStep = nextStep * 2;
                }
            } else if (duration < SEGMENT_DURATION * 2) {
                //do nothing with nextStep
            } else {
                // 如果>=30分钟才耗尽号段，则说明号段过长，进行缩小，缩小一半，但是不能小于最小步长
                nextStep = nextStep / 2 >= buffer.getMinStep() ? nextStep / 2 : nextStep;
            }
            logger.info("leafKey[{}], step[{}], duration[{}mins], nextStep[{}]", key, buffer.getStep(), String.format("%.2f",((double)duration / (1000 * 60))), nextStep);
            LeafAlloc temp = new LeafAlloc();
            temp.setKey(key);
            temp.setStep(nextStep);
            // 更新DB中号段
            leafAlloc = dao.updateMaxIdByCustomStepAndGetLeafAlloc(temp);
            // 更新缓存中号段
            buffer.setUpdateTimestamp(System.currentTimeMillis());
            buffer.setStep(nextStep);
            buffer.setMinStep(leafAlloc.getStep());//leafAlloc的step为DB中的step
        }
        // must set value before set max
        long value = leafAlloc.getMaxId() - buffer.getStep();
        segment.getValue().set(value);
        segment.setMax(leafAlloc.getMaxId());
        segment.setStep(buffer.getStep());
        sw.stop("updateSegmentFromDb", key + " " + segment);
    }

    public Result getIdFromSegmentBuffer(final SegmentBuffer buffer) {
        while (true) {
            buffer.rLock().lock();
            try {
                final Segment segment = buffer.getCurrent();
                if (!buffer.isNextReady() && (segment.getIdle() < 0.9 * segment.getStep()) && buffer.getThreadRunning().compareAndSet(false, true)) {
                    service.execute(new Runnable() {
                        @Override
                        public void run() {
                            Segment next = buffer.getSegments()[buffer.nextPos()];
                            boolean updateOk = false;
                            try {
                                updateSegmentFromDb(buffer.getKey(), next);
                                updateOk = true;
                                logger.info("update segment {} from db {}", buffer.getKey(), next);
                            } catch (Exception e) {
                                logger.warn(buffer.getKey() + " updateSegmentFromDb exception", e);
                            } finally {
                                if (updateOk) {
                                    buffer.wLock().lock();
                                    buffer.setNextReady(true);
                                    buffer.getThreadRunning().set(false);
                                    buffer.wLock().unlock();
                                } else {
                                    buffer.getThreadRunning().set(false);
                                }
                            }
                        }
                    });
                }
                long value = segment.getValue().getAndIncrement();
                if (value < segment.getMax()) {
                    return new Result(value, Status.SUCCESS);
                }
            } finally {
                buffer.rLock().unlock();
            }
            waitAndSleep(buffer);
            buffer.wLock().lock();
            try {
                final Segment segment = buffer.getCurrent();
                long value = segment.getValue().getAndIncrement();
                if (value < segment.getMax()) {
                    return new Result(value, Status.SUCCESS);
                }
                if (buffer.isNextReady()) {
                    buffer.switchPos();
                    buffer.setNextReady(false);
                } else {
                    logger.error("Both two segments in {} are not ready!", buffer);
                    return new Result(EXCEPTION_ID_TWO_SEGMENTS_ARE_NULL, Status.EXCEPTION);
                }
            } finally {
                buffer.wLock().unlock();
            }
        }
    }

    private void waitAndSleep(SegmentBuffer buffer) {
        int roll = 0;
        while (buffer.getThreadRunning().get()) {
            roll += 1;
            if(roll > 10000) {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                    break;
                } catch (InterruptedException e) {
                    logger.warn("Thread {} Interrupted",Thread.currentThread().getName());
                    break;
                }
            }
        }
    }

    public List<LeafAlloc> getAllLeafAllocs() {
        return dao.getAllLeafAllocs();
    }

    public Map<String, SegmentBuffer> getCache() {
        return cache;
    }

    public IDAllocDao getDao() {
        return dao;
    }

    public void setDao(IDAllocDao dao) {
        this.dao = dao;
    }
}
