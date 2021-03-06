package npserver.utils;

import npserver.UdpServer;
import npserver.handler.ServerHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class UdpConnManagement {
    private static final Logger LOGGER = LogManager.getLogger(UdpConnManagement.class);
    public static class IPInfo{
        public InetAddress host;
        public int port;

        public IPInfo(InetAddress host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static AtomicReference<Map<String, String>> pairsRef = new AtomicReference<>(new HashMap<>());
    private static AtomicReference<Map<String, String>> userUdpConn = new AtomicReference<>(new HashMap<>());

    private static AtomicReference<Map<String, IPInfo>> userAddr = new AtomicReference<>(new HashMap<>());

    public static String createAddr(String host, int port){
        return String.format("/%s:%d", host, port);
    }


    public synchronized static String getUserByConn(String addr) {
        return userUdpConn.get().get(addr);
    }

    public synchronized static void addMapping(String username, InetAddress host, int port){
        String strAdd = createAddr(host.getHostAddress(), port);
        IPInfo ipInfo = new IPInfo(host, port);

        for (Map.Entry<String, String> e : userUdpConn.get().entrySet()) {
            if (e.getValue().equals(username)) {
                userUdpConn.get().remove(e.getKey());
                break;
            }
        }
        LOGGER.info("{}: map ==> ({})", strAdd, username);
        userUdpConn.get().put(strAdd, username);
        userAddr.get().put(username, ipInfo);

//        userUdpConn.get().put(strAdd, username);

//        if(userUdpConn.get().get(strAdd) == null && userAddr.get().get(username) == null ){

//        }
    }

    public synchronized static IPInfo getPartnerIpInfo(String sender){
        String partner = pairsRef.get().get(sender);
        if(partner == null) {
            LOGGER.error("{}: Pair not found", sender);
            return null;
        }
        else {
            IPInfo info = userAddr.get().get(partner);
            if(info == null) LOGGER.error("{}: Partner IPInfo not found", sender);
            return info;
        }
    }

    public synchronized static void tcpRemovePair(String user1, String user2){
        pairsRef.get().remove(user1);
        pairsRef.get().remove(user2);
    }

    public synchronized static String tcpRemovePair(String user){
        if(pairsRef.get().get(user) != null){
            String user2 = pairsRef.get().get(user);
            pairsRef.get().remove(user);
            pairsRef.get().remove(user2);
            return user2;
        }
        return null;
    }


    public synchronized static void tcpAddPair(String user1, String user2){
        pairsRef.get().put(user1, user2);
        pairsRef.get().put(user2, user1);
    }
}
