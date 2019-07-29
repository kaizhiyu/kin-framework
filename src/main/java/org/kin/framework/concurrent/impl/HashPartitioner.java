package org.kin.framework.concurrent.impl;

import org.kin.framework.concurrent.Partitioner;
import org.kin.framework.utils.HashUtils;

/**
 * Created by huangjianqin on 2017/10/26.
 * HashTable的Hash方式
 */
public class HashPartitioner<K> implements Partitioner<K> {
    @Override
    public int toPartition(K key, int numPartition) {
        return HashUtils.hash(key, numPartition);
    }
}
