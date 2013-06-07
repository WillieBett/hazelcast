/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.test;

import com.hazelcast.cluster.AbstractJoiner;
import com.hazelcast.cluster.ClusterServiceImpl;
import com.hazelcast.cluster.Joiner;
import com.hazelcast.instance.AddressPicker;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.NodeContext;
import com.hazelcast.nio.*;
import com.hazelcast.spi.ExecutionService;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.util.ExceptionUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class TestNodeRegistry {

    private final Address[] addresses;
    private final ConcurrentMap<Address, NodeEngineImpl> nodes = new ConcurrentHashMap<Address, NodeEngineImpl>(10);

    TestNodeRegistry(Address[] addresses) {
        this.addresses = addresses;
    }

    NodeContext createNodeContext(Address address) {
        return new MockNodeContext(address);
    }

    private void register(Address address, NodeEngineImpl nodeEngine) {
        nodes.put(address, nodeEngine);
    }

    void shutdown() {
        final Collection<NodeEngineImpl> values = new ArrayList<NodeEngineImpl>(nodes.values());
        nodes.clear();
        for (NodeEngineImpl value : values) {
            value.getHazelcastInstance().getLifecycleService().shutdown();
        }
    }

    private class MockNodeContext implements NodeContext {

        final Address thisAddress;

        public MockNodeContext(Address thisAddress) {
            this.thisAddress = thisAddress;
        }

        public AddressPicker createAddressPicker(Node node) {
            return new StaticAddressPicker();
        }

        public Joiner createJoiner(Node node) {
            return new MockJoiner(node);
        }

        public ConnectionManager createConnectionManager(Node node, ServerSocketChannel serverSocketChannel) {
            return new MockConnectionManager(node);
        }

        private class MockConnectionManager implements ConnectionManager {
            final Map<Address, Connection> mapConnections = new ConcurrentHashMap<Address, Connection>(10);
            final Node node;
            final Connection thisConnection;

            MockConnectionManager(Node node) {
                this.node = node;
                thisConnection = new MockConnection(node.getThisAddress(), node.nodeEngine);
                register(node.getThisAddress(), node.nodeEngine);
            }

            public Connection getConnection(Address address) {
                Connection conn = mapConnections.get(address);
                if (conn == null) {
                    NodeEngineImpl nodeEngine = nodes.get(address);
                    conn = new MockConnection(address, nodeEngine);
                    mapConnections.put(address, conn);
                }
                return conn;
            }

            public Connection getOrConnect(Address address) {
                return getConnection(address);
            }

            public Connection getOrConnect(Address address, boolean silent) {
                return getConnection(address);
            }

            public void shutdown() {
                for (Address address : addresses) {
                    if (address.equals(thisAddress)) continue;

                    final NodeEngineImpl nodeEngine = nodes.get(address);
                    if (nodeEngine != null && nodeEngine.isActive()) {
                        nodeEngine.getExecutionService().execute(ExecutionService.SYSTEM_EXECUTOR, new Runnable() {
                            public void run() {
                                final ClusterServiceImpl clusterService = (ClusterServiceImpl) nodeEngine.getClusterService();
                                clusterService.removeAddress(thisAddress);
                            }
                        });
                    }
                }
            }

            public void start() {
            }

            public void addConnectionListener(ConnectionListener connectionListener) {
            }

            public Map<Address, Connection> getReadonlyConnectionMap() {
                return null;
            }

            public void destroyConnection(Connection conn) {
            }

            public void restart() {
            }

            public int getCurrentClientConnections() {
                return 0;
            }

            public int getAllTextConnections() {
                return 0;
            }

            private class MockConnection implements Connection {
                final Address endpoint;
                final NodeEngineImpl nodeEngine;

                public MockConnection(Address address, NodeEngineImpl nodeEngine) {
                    this.endpoint = address;
                    this.nodeEngine = nodeEngine;
                }

                public Address getEndPoint() {
                    return endpoint;
                }

                public boolean live() {
                    return true;
                }

                public boolean write(SocketWritable socketWritable) {
                    final Packet packet = (Packet) socketWritable;
                    if (nodeEngine.getNode().isActive()) {
                        packet.setConn(thisConnection);
                        nodeEngine.handlePacket(packet);
                        return true;
                    }
                    return false;
                }

                public long lastReadTime() {
                    return System.currentTimeMillis();
                }

                public long lastWriteTime() {
                    return System.currentTimeMillis();
                }

                public void close() {
                }

                public boolean isClient() {
                    return false;
                }

                public InetAddress getInetAddress() {
                    try {
                        return thisAddress.getInetAddress();
                    } catch (UnknownHostException e) {
                        throw ExceptionUtil.rethrow(e);
                    }
                }

                public InetSocketAddress getRemoteSocketAddress() {
                    return new InetSocketAddress(getInetAddress(), getPort());
                }

                public int getPort() {
                    return thisAddress.getPort();
                }
            }
        }

        private class StaticAddressPicker implements AddressPicker {
            public void pickAddress() throws Exception {
            }

            public Address getBindAddress() {
                return thisAddress;
            }

            public Address getPublicAddress() {
                return thisAddress;
            }

            public ServerSocketChannel getServerSocketChannel() {
                return null;
            }
        }

        private class MockJoiner extends AbstractJoiner {

            MockJoiner(Node node) {
                super(node);
            }

            public void doJoin(AtomicBoolean joined) {
                Address master = null;
                for (Address address : addresses) {
                    final NodeEngineImpl nodeEngine = nodes.get(address);
                    if (nodeEngine != null && nodeEngine.getNode().isActive()) {
                        master = address;
                        break;
                    }
                }
                node.setMasterAddress(master);
                if (node.getMasterAddress().equals(node.getThisAddress())) {
                    node.setJoined();
                } else {
                    for (int i = 0; !node.joined() && i < 1000; i++) {
                        try {
                            node.clusterService.sendJoinRequest(node.getMasterAddress(), true);
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    if (!node.joined()) {
                        throw new AssertionError("Node[" + thisAddress + "] should have been joined to "
                                + node.getMasterAddress());
                    }
                }
            }

            public void searchForOtherClusters() {
            }

            public String toString() {
                return "MockJoiner";
            }
        }
    }
}
