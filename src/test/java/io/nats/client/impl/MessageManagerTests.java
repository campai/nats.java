// Copyright 2021 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import io.nats.client.*;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.support.IncomingHeadersProcessor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static io.nats.client.support.NatsJetStreamConstants.*;
import static io.nats.client.support.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SameParameterValue")
public class MessageManagerTests extends JetStreamTestBase {

    @Test
    public void testConstruction() throws Exception {
        runInJsServer(nc -> {
            NatsJetStreamSubscription sub = genericPushSub(nc);
            _pushConstruction(nc, true, true, push_hb_fc(), sub);
            _pushConstruction(nc, true, false, push_hb_xfc(), sub);
            _pushConstruction(nc, false, false, push_xhb_xfc(), sub);
        });
    }

    private void tf(Consumer<Boolean> c) {
        for (int tf = 0; tf < 2; tf++) {
            c.accept(tf == 0);
        }
    }

    private void _pushConstruction(Connection conn, boolean hb, boolean fc, SubscribeOptions so, NatsJetStreamSubscription sub) {
        tf(ordered -> tf(syncMode -> tf(queueMode -> {
            PushMessageManager manager = getPushManager(conn, so, sub, ordered, syncMode, queueMode);
            assertEquals(syncMode, manager.isSyncMode());
            assertEquals(queueMode, manager.isQueueMode());
            if (queueMode) {
                assertFalse(manager.isHb());
                assertFalse(manager.isFc());
            }
            else {
                assertEquals(hb, manager.isHb());
                assertEquals(fc, manager.isFc());
            }
        })));
    }

    @Test
    public void testPushBeforeQueueProcessorAndManage() throws Exception {
        TestHandler handler = new TestHandler();
        runInJsServer(handler, nc -> {
            NatsJetStreamSubscription sub = genericPushSub(nc);

            PushMessageManager pushMgr = getPushManager(nc, push_hb_fc(), sub, false, true, false);
            testPushBqpAndManage(sub, handler, pushMgr);

            pushMgr = getPushManager(nc, push_hb_xfc(), sub, false, true, false);
            testPushBqpAndManage(sub, handler, pushMgr);

            pushMgr = getPushManager(nc, push_xhb_xfc(), sub, false, true, false);
            testPushBqpAndManage(sub, handler, pushMgr);

            pushMgr = getPushManager(nc, push_hb_fc(), sub, false, false, false);
            testPushBqpAndManage(sub, handler, pushMgr);

            pushMgr = getPushManager(nc, push_hb_xfc(), sub, false, false, false);
            testPushBqpAndManage(sub, handler, pushMgr);

            pushMgr = getPushManager(nc, push_xhb_xfc(), sub, false, false, false);
            testPushBqpAndManage(sub, handler, pushMgr);
        });
    }

    private void testPushBqpAndManage(NatsJetStreamSubscription sub, TestHandler handler, PushMessageManager manager) {
        handler.reset();
        String sid = sub.getSID();

        assertTrue(manager.beforeQueueProcessorImpl(getTestJsMessage(1, sid)));
        assertFalse(manager.manage(getTestJsMessage(1, sid)));

        assertEquals(!manager.hb, manager.beforeQueueProcessorImpl(getHeartbeat(sid)));

        List<Integer> unhandledCodes = new ArrayList<>();
        assertTrue(manager.beforeQueueProcessorImpl(getFlowControl(1, sid)));
        assertTrue(manager.beforeQueueProcessorImpl(getFcHeartbeat(1, sid)));
        if (manager.fc) {
            assertTrue(manager.manage(getFlowControl(1, sid)));
            assertTrue(manager.manage(getFcHeartbeat(1, sid)));
        }
        else {
            if (manager.syncMode) {
                assertThrows(JetStreamStatusException.class, () -> manager.manage(getFlowControl(1, sid)));
                assertThrows(JetStreamStatusException.class, () -> manager.manage(getFcHeartbeat(1, sid)));
            }
            else {
                manager.manage(getFlowControl(1, sid));
                manager.manage(getFcHeartbeat(1, sid));
            }
            unhandledCodes.add(FLOW_OR_HEARTBEAT_STATUS_CODE); // fc
            unhandledCodes.add(FLOW_OR_HEARTBEAT_STATUS_CODE); // hb
        }

        assertTrue(manager.beforeQueueProcessorImpl(getUnkownStatus(sid)));
        if (manager.syncMode) {
            assertThrows(JetStreamStatusException.class, () -> manager.manage(getUnkownStatus(sid)));
        }
        else {
            manager.manage(getUnkownStatus(sid));
        }
        unhandledCodes.add(999);

        sleep(100);
        List<TestHandler.StatusEvent> list = handler.getUnhandledStatuses();
        assertEquals(unhandledCodes.size(), list.size());
        for (int x = 0; x < list.size(); x++) {
            TestHandler.StatusEvent se = list.get(x);
            assertSame(sub.getSID(), se.sid);
            assertEquals(unhandledCodes.get(x), se.status.getCode());
        }
    }

    @Test
    public void testPullBeforeQueueProcessorAndManage() throws Exception {
        TestHandler handler = new TestHandler();
        runInJsServer(handler, nc -> {
            NatsJetStreamSubscription sub = genericPullSub(nc);

            PullMessageManager pullMgr = getPullManager(nc, sub, true);
            pullMgr.startPullRequest(PullRequestOptions.builder(1).build());
            testPullBqpAndManage(sub, handler, pullMgr);

            pullMgr = getPullManager(nc, sub, true);
            pullMgr.startPullRequest(PullRequestOptions.builder(1).idleHeartbeat(100).build());
            testPullBqpAndManage(sub, handler, pullMgr);
        });
    }

    @Test
    public void testPushManagerHeartbeats() throws Exception {
        TestHandler handler = new TestHandler();
        runInJsServer(handler, nc -> {
            PushMessageManager pushMgr = getPushManager(nc, push_xhb_xfc(), null, false, true, false);
            NatsJetStreamSubscription sub = mockSub((NatsConnection)nc, pushMgr);
            pushMgr.startup(sub);
            List<TestHandler.HeartbeatAlarmEvent> list = handler.getHeartbeatAlarms();
            assertEquals(0, list.size());

            pushMgr = getPushManager(nc, push_xhb_xfc(), null, false, false, false);
            sub = mockSub((NatsConnection)nc, pushMgr);
            pushMgr.startup(sub);
            list = handler.getHeartbeatAlarms();
            assertEquals(0, list.size());

            PushSubscribeOptions pso = ConsumerConfiguration.builder().idleHeartbeat(100).buildPushSubscribeOptions();
            pushMgr = getPushManager(nc, pso, null, false, true, false);
            sub = mockSub((NatsConnection)nc, pushMgr);
            pushMgr.startup(sub);
            // give time for heartbeats to be missed
            sleep(400);
            list = handler.getHeartbeatAlarms();
            assertTrue(list.size() > 0);

            pushMgr = getPushManager(nc, pso, null, false, false, false);
            sub = mockSub((NatsConnection)nc, pushMgr);
            pushMgr.startup(sub);
            // give time for heartbeats to be missed
            sleep(400);
            list = handler.getHeartbeatAlarms();
            assertTrue(list.size() > 0);
        });
    }

    @Test
    public void testPullManagerHeartbeats() throws Exception {
        TestHandler handler = new TestHandler();
        runInJsServer(handler, nc -> {
            PullMessageManager pullMgr = getPullManager(nc, null, true);
            NatsJetStreamSubscription sub = mockSub((NatsConnection)nc, pullMgr);
            pullMgr.startup(sub);
            pullMgr.startPullRequest(PullRequestOptions.builder(1).build());
            assertEquals(0, handler.getHeartbeatAlarms().size());
            assertNull(pullMgr.heartbeatTimer);

            handler.reset();
            pullMgr.startPullRequest(PullRequestOptions.builder(1).idleHeartbeat(100).build());
            sleep(400); // give time for heartbeats to be missed
            assertTrue(handler.getHeartbeatAlarms().size() > 0);
            assertNotNull(pullMgr.heartbeatTimer);

            handler.reset();
            pullMgr.startPullRequest(PullRequestOptions.builder(1).idleHeartbeat(100).build());
            sleep(400); // give time for heartbeats to be missed
            assertTrue(handler.getHeartbeatAlarms().size() > 0);
            assertNotNull(pullMgr.heartbeatTimer);

            handler.reset();
            pullMgr.startPullRequest(PullRequestOptions.builder(1).build());
            sleep(400); // give time for heartbeats to be missed
            assertEquals(0, handler.getHeartbeatAlarms().size());
            assertNull(pullMgr.heartbeatTimer);
        });
    }

    private void testPullBqpAndManage(NatsJetStreamSubscription sub, TestHandler handler, PullMessageManager manager) {
        handler.reset();
        String sid = sub.getSID();

        assertTrue(manager.beforeQueueProcessorImpl(getTestJsMessage(1, sid)));
        assertFalse(manager.manage(getTestJsMessage(1, sid)));

        // tracks but doesn't queue
        assertFalse(manager.beforeQueueProcessorImpl(getHeartbeat(sid)));
        assertFalse(manager.beforeQueueProcessorImpl(getHeartbeat(sid)));
        assertFalse(manager.beforeQueueProcessorImpl(getNotFoundStatus(sid)));
        assertFalse(manager.beforeQueueProcessorImpl(getRequestTimeoutStatus(sid)));

        // warnings get queued
        assertTrue(manager.beforeQueueProcessorImpl(getConflictStatus(sid, MESSAGE_SIZE_EXCEEDS_MAX_BYTES)));
        assertTrue(manager.beforeQueueProcessorImpl(getConflictStatus(sid, EXCEEDED_MAX_WAITING)));
        assertTrue(manager.beforeQueueProcessorImpl(getConflictStatus(sid, EXCEEDED_MAX_REQUEST_BATCH)));
        assertTrue(manager.beforeQueueProcessorImpl(getConflictStatus(sid, EXCEEDED_MAX_REQUEST_EXPIRES)));
        assertTrue(manager.beforeQueueProcessorImpl(getConflictStatus(sid, EXCEEDED_MAX_REQUEST_MAX_BYTES)));

        manager.manage(getConflictStatus(sid, MESSAGE_SIZE_EXCEEDS_MAX_BYTES));
        manager.manage(getConflictStatus(sid, EXCEEDED_MAX_WAITING));
        manager.manage(getConflictStatus(sid, EXCEEDED_MAX_REQUEST_BATCH));
        manager.manage(getConflictStatus(sid, EXCEEDED_MAX_REQUEST_EXPIRES));
        manager.manage(getConflictStatus(sid, EXCEEDED_MAX_REQUEST_MAX_BYTES));

        // errors get queued
        assertTrue(manager.beforeQueueProcessorImpl(getBadRequest(sid)));
        assertTrue(manager.beforeQueueProcessorImpl(getUnkownStatus(sid)));
        assertTrue(manager.beforeQueueProcessorImpl(getConflictStatus(sid, CONSUMER_DELETED)));
        assertTrue(manager.beforeQueueProcessorImpl(getConflictStatus(sid, CONSUMER_IS_PUSH_BASED)));

        if (manager.syncMode) {
            assertThrows(JetStreamStatusException.class, () -> manager.manage(getBadRequest(sid)));
            assertThrows(JetStreamStatusException.class, () -> manager.manage(getUnkownStatus(sid)));
            assertThrows(JetStreamStatusException.class, () -> manager.manage(getConflictStatus(sid, CONSUMER_DELETED)));
            assertThrows(JetStreamStatusException.class, () -> manager.manage(getConflictStatus(sid, CONSUMER_IS_PUSH_BASED)));
        }
        else {
            manager.manage(getBadRequest(sid));
            manager.manage(getUnkownStatus(sid));
            manager.manage(getConflictStatus(sid, CONSUMER_DELETED));
            manager.manage(getConflictStatus(sid, CONSUMER_IS_PUSH_BASED));
        }

        sleep(100);

        List<TestHandler.StatusEvent> list = handler.getPullStatusWarnings();
        assertEquals(5, list.size());
        for (int x = 0; x < list.size(); x++) {
            TestHandler.StatusEvent se = list.get(x);
            assertSame(sub.getSID(), se.sid);
            assertEquals(CONFLICT_CODE, se.status.getCode());
        }

        list = handler.getPullStatusErrors();
        assertEquals(4, list.size());
        int[] codes = new int[]{BAD_REQUEST_CODE, 999, CONFLICT_CODE, CONFLICT_CODE};
        for (int x = 0; x < list.size(); x++) {
            TestHandler.StatusEvent se = list.get(x);
            assertSame(sub.getSID(), se.sid);
            assertEquals(codes[x], se.status.getCode());
        }
    }

    @Test
    public void test_push_fc() {
        SubscribeOptions so = push_hb_fc();
        MockPublishInternal mpi = new MockPublishInternal();
        PushMessageManager pmm = new PushMessageManager(mpi, null, null, so, so.getConsumerConfiguration(), false, true);
        NatsJetStreamSubscription sub = mockSub(mpi, pmm);
        String sid = sub.getSID();
        pmm.startup(sub);

        assertNull(pmm.getLastFcSubject());
        pmm.manage(getFlowControl(1, sid));
        assertEquals(getFcSubject(1), pmm.getLastFcSubject());
        assertEquals(getFcSubject(1), mpi.fcSubject);
        assertEquals(1, mpi.pubCount);

        pmm.manage(getFlowControl(1, sid)); // duplicate should not call publish
        assertEquals(getFcSubject(1), pmm.getLastFcSubject());
        assertEquals(getFcSubject(1), mpi.fcSubject);
        assertEquals(1, mpi.pubCount);

        pmm.manage(getFlowControl(2, sid)); // duplicate should not call publish
        assertEquals(getFcSubject(2), pmm.getLastFcSubject());
        assertEquals(getFcSubject(2), mpi.fcSubject);
        assertEquals(2, mpi.pubCount);

        pmm.manage(getFcHeartbeat(2, sid)); // duplicate should not call publish
        assertEquals(getFcSubject(2), pmm.getLastFcSubject());
        assertEquals(getFcSubject(2), mpi.fcSubject);
        assertEquals(2, mpi.pubCount);

        pmm.manage(getFcHeartbeat(3, sid));
        assertEquals(getFcSubject(3), pmm.getLastFcSubject());
        assertEquals(getFcSubject(3), mpi.fcSubject);
        assertEquals(3, mpi.pubCount);

        assertThrows(JetStreamStatusException.class, () -> pmm.manage(getHeartbeat(sid)));
        assertEquals(getFcSubject(3), pmm.getLastFcSubject());
        assertEquals(getFcSubject(3), mpi.fcSubject);
        assertEquals(3, mpi.pubCount);

        // coverage sequences
        pmm.manage(getTestJsMessage(1, sid));
        assertEquals(1, pmm.getLastStreamSequence());
        assertEquals(1, pmm.getLastConsumerSequence());

        pmm.manage(getTestJsMessage(2, sid));
        assertEquals(2, pmm.getLastStreamSequence());
        assertEquals(2, pmm.getLastConsumerSequence());

        // coverage extractFcSubject
        assertNull(pmm.extractFcSubject(getTestJsMessage(4, sid)));
        assertNull(pmm.extractFcSubject(getHeartbeat(sid)));
        assertNotNull(pmm.extractFcSubject(getFcHeartbeat(9, sid)));
    }

    @Test
    public void test_push_xfc() {
        _push_xfc(push_hb_xfc());
        _push_xfc(push_xhb_xfc());
    }

    private void _push_xfc(SubscribeOptions so) {
        MockPublishInternal mpi = new MockPublishInternal();
        PushMessageManager pmm = new PushMessageManager(mpi, null, null, so, so.getConsumerConfiguration(), false, true);
        NatsJetStreamSubscription sub = mockSub(mpi, pmm);
        String sid = sub.getSID();
        pmm.startup(sub);
        assertNull(pmm.getLastFcSubject());

        assertThrows(JetStreamStatusException.class, () -> pmm.manage(getFlowControl(1, sid)));
        assertNull(pmm.getLastFcSubject());
        assertNull(mpi.fcSubject);
        assertEquals(0, mpi.pubCount);

        assertThrows(JetStreamStatusException.class, () -> pmm.manage(getHeartbeat(sid)));
        assertNull(pmm.getLastFcSubject());
        assertNull(mpi.fcSubject);
        assertEquals(0, mpi.pubCount);

        // coverage sequences
        pmm.manage(getTestJsMessage(1, sid));
        assertEquals(1, pmm.getLastStreamSequence());
        assertEquals(1, pmm.getLastConsumerSequence());

        pmm.manage(getTestJsMessage(2, sid));
        assertEquals(2, pmm.getLastStreamSequence());
        assertEquals(2, pmm.getLastConsumerSequence());

        // coverage beforeQueueProcessor
        assertTrue(pmm.beforeQueueProcessorImpl(getFlowControl(1, sid)));
        assertTrue(pmm.beforeQueueProcessorImpl(getUnkownStatus(sid)));
        assertTrue(pmm.beforeQueueProcessorImpl(getFcHeartbeat(1, sid)));
        assertTrue(pmm.beforeQueueProcessorImpl(getTestJsMessage(1, sid)));

        // coverage manager
        assertFalse(pmm.manage(getTestJsMessage(1, sid)));
        assertThrows(JetStreamStatusException.class, () -> pmm.manage(getFlowControl(1, sid)));
        assertThrows(JetStreamStatusException.class, () -> pmm.manage(getFcHeartbeat(1, sid)));

        // coverage beforeQueueProcessor
        assertTrue(pmm.beforeQueueProcessorImpl(getTestJsMessage(3, sid)));
        assertTrue(pmm.beforeQueueProcessorImpl(getRequestTimeoutStatus(sid)));
        assertTrue(pmm.beforeQueueProcessorImpl(getFcHeartbeat(9, sid)));
        assertEquals(!pmm.hb, pmm.beforeQueueProcessorImpl(getHeartbeat(sid)));

        // coverage extractFcSubject
        assertNull(pmm.extractFcSubject(getTestJsMessage()));
        assertNull(pmm.extractFcSubject(getHeartbeat(sid)));
        assertNotNull(pmm.extractFcSubject(getFcHeartbeat(9, sid)));
    }

    @Test
    public void test_received_time() throws Exception {
        runInJsServer(nc -> {
            createDefaultTestStream(nc);
            JetStream js = nc.jetStream();
            JetStreamManagement jsm = nc.jetStreamManagement();

            _received_time_yes(push_hb_fc(), js);
            _received_time_yes(push_hb_xfc(), js);
            _received_time_no(js, jsm, js.subscribe(SUBJECT, push_xhb_xfc()));
        });
    }

    private void _received_time_yes(PushSubscribeOptions so, JetStream js) throws Exception {
        long before = System.currentTimeMillis();
        NatsJetStreamSubscription sub = (NatsJetStreamSubscription) js.subscribe(SUBJECT, so);

        // during the sleep, the heartbeat is delivered and is checked
        // by the heartbeat listener and recorded as received
        sleep(1050); // slightly longer than the idle heartbeat

        long preTime = findStatusManager(sub).getLastMsgReceived();
        assertTrue(preTime > before);
        sub.unsubscribe();
    }

    PushMessageManager findStatusManager(NatsJetStreamSubscription sub) {
        MessageManager mm = sub.getManager();
        if (mm instanceof PushMessageManager) {
            return (PushMessageManager)mm;
        }
        return null;
    };

    private void _received_time_no(JetStream js, JetStreamManagement jsm, JetStreamSubscription sub) throws IOException, JetStreamApiException, InterruptedException {
        js.publish(SUBJECT, dataBytes(0));
        sub.nextMessage(1000);
        NatsJetStreamSubscription nsub = (NatsJetStreamSubscription)sub;
        assertTrue(findStatusManager(nsub).getLastMsgReceived() <= System.currentTimeMillis());
        jsm.purgeStream(STREAM);
        sub.unsubscribe();
    }

    @Test
    public void test_hb_yes_settings() throws Exception {
        runInJsServer(nc -> {
            NatsJetStreamSubscription sub = genericPushSub(nc);

            ConsumerConfiguration cc = ConsumerConfiguration.builder().idleHeartbeat(1000).build();

            // MessageAlarmTime default
            PushSubscribeOptions so = new PushSubscribeOptions.Builder().configuration(cc).build();
            PushMessageManager manager = getPushManager(nc, so, sub, false);
            assertEquals(1000, manager.getIdleHeartbeatSetting());
            assertEquals(3000, manager.getAlarmPeriodSetting());

            // MessageAlarmTime < idleHeartbeat
            so = new PushSubscribeOptions.Builder().configuration(cc).messageAlarmTime(999).build();
            manager = getPushManager(nc, so, sub, false);
            assertEquals(1000, manager.getIdleHeartbeatSetting());
            assertEquals(3000, manager.getAlarmPeriodSetting());

            // MessageAlarmTime == idleHeartbeat
            so = new PushSubscribeOptions.Builder().configuration(cc).messageAlarmTime(1000).build();
            manager = getPushManager(nc, so, sub, false);
            assertEquals(1000, manager.getIdleHeartbeatSetting());
            assertEquals(1000, manager.getAlarmPeriodSetting());

            // MessageAlarmTime > idleHeartbeat
            so = new PushSubscribeOptions.Builder().configuration(cc).messageAlarmTime(2000).build();
            manager = getPushManager(nc, so, sub, false);
            assertEquals(1000, manager.getIdleHeartbeatSetting());
            assertEquals(2000, manager.getAlarmPeriodSetting());
        });
    }

    @Test
    public void test_hb_no_settings() throws Exception {
        runInJsServer(nc -> {
            NatsJetStreamSubscription sub = genericPushSub(nc);
            SubscribeOptions so = push_xhb_xfc();
            PushMessageManager manager = getPushManager(nc, so, sub, false);
            assertEquals(0, manager.getIdleHeartbeatSetting());
            assertEquals(0, manager.getAlarmPeriodSetting());
        });
    }

    private ConsumerConfiguration cc_fc_hb() {
        return ConsumerConfiguration.builder().flowControl(1000).build();
    }

    private ConsumerConfiguration cc_xfc_hb() {
        return ConsumerConfiguration.builder().idleHeartbeat(1000).build();
    }

    private ConsumerConfiguration cc_xfc_xhb() {
        return ConsumerConfiguration.builder().build();
    }

    private PushSubscribeOptions push_hb_fc() {
        return new PushSubscribeOptions.Builder().configuration(cc_fc_hb()).build();
    }

    private PushSubscribeOptions push_hb_xfc() {
        return new PushSubscribeOptions.Builder().configuration(cc_xfc_hb()).build();
    }

    private PushSubscribeOptions push_xhb_xfc() {
        return new PushSubscribeOptions.Builder().configuration(cc_xfc_xhb()).build();
    }

    private PushMessageManager getPushManager(Connection conn, SubscribeOptions so, NatsJetStreamSubscription sub, boolean ordered) {
        return getPushManager(conn, so, sub, ordered, true, false);
    }

    private PushMessageManager getPushManager(Connection conn, SubscribeOptions so, NatsJetStreamSubscription sub, boolean ordered, boolean syncMode, boolean queueMode) {
        PushMessageManager manager;
        if (ordered) {
            manager = new OrderedMessageManager((NatsConnection) conn, null, null, so, so.getConsumerConfiguration(), queueMode, syncMode);
        }
        else {
            manager = new PushMessageManager((NatsConnection) conn, null, null, so, so.getConsumerConfiguration(), queueMode, syncMode);
        }
        if (sub != null) {
            manager.startup(sub);
        }
        return manager;
    }

    private PullMessageManager getPullManager(Connection conn, NatsJetStreamSubscription sub, boolean syncMode) {
        PullMessageManager manager = new PullMessageManager((NatsConnection) conn, syncMode);
        if (sub != null) {
            manager.startup(sub);
        }
        return manager;
    }

    private NatsMessage getFlowControl(int replyToId, String sid) {
        IncomingMessageFactory imf = new IncomingMessageFactory(sid, "subj", getFcSubject(replyToId), 0, false);
        imf.setHeaders(new IncomingHeadersProcessor(("NATS/1.0 " + FLOW_OR_HEARTBEAT_STATUS_CODE + " " + FLOW_CONTROL_TEXT + "\r\n").getBytes()));
        return imf.getMessage();
    }

    private String getFcSubject(int id) {
        return "fcSubject." + id;
    }

    private NatsMessage getFcHeartbeat(int replyToId, String sid) {
        IncomingMessageFactory imf = new IncomingMessageFactory(sid, "subj", null, 0, false);
        String s = "NATS/1.0 " + FLOW_OR_HEARTBEAT_STATUS_CODE + " " + HEARTBEAT_TEXT + "\r\n" + CONSUMER_STALLED_HDR + ":" + getFcSubject(replyToId) + "\r\n\r\n";
        imf.setHeaders(new IncomingHeadersProcessor(s.getBytes()));
        return imf.getMessage();
    }

    private NatsMessage getHeartbeat(String sid) {
        IncomingMessageFactory imf = new IncomingMessageFactory(sid, "subj", null, 0, false);
        String s = "NATS/1.0 " + FLOW_OR_HEARTBEAT_STATUS_CODE + " " + HEARTBEAT_TEXT + "\r\n";
        imf.setHeaders(new IncomingHeadersProcessor(s.getBytes()));
        return imf.getMessage();
    }

    private NatsMessage getBadRequest(String sid) {
        return getStatus(BAD_REQUEST_CODE, BAD_REQUEST, sid);
    }

    private NatsMessage getNotFoundStatus(String sid) {
        return getStatus(NOT_FOUND_CODE, NO_MESSAGES, sid);
    }

    private NatsMessage getRequestTimeoutStatus(String sid) {
        return getStatus(REQUEST_TIMEOUT_CODE, "expired", sid);
    }

    private NatsMessage getConflictStatus(String sid, String message) {
        return getStatus(CONFLICT_CODE, message, sid);
    }

    private NatsMessage getUnkownStatus(String sid) {
        return getStatus(999, "unknown", sid);
    }

    private NatsMessage getStatus(int code, String message, String sid) {
        IncomingMessageFactory imf = new IncomingMessageFactory(sid, "subj", null, 0, false);
        imf.setHeaders(new IncomingHeadersProcessor(("NATS/1.0 " + code + " " + message + "\r\n").getBytes()));
        return imf.getMessage();
    }

    static class MockPublishInternal extends NatsConnection {
        int pubCount;
        String fcSubject;

        public MockPublishInternal() {
            this(new Options.Builder().errorListener(new ErrorListener() {}).build());
        }

        public MockPublishInternal(Options options) {
            super(options);
        }

        @Override
        void publishInternal(String subject, String replyTo, Headers headers, byte[] data) {
            fcSubject = subject;
            ++pubCount;
        }
    }

    static AtomicInteger ID = new AtomicInteger();
    private static NatsJetStreamSubscription genericPushSub(Connection nc) throws IOException, JetStreamApiException {
        String subject = genericSub(nc);
        JetStream js = nc.jetStream();
        return (NatsJetStreamSubscription) js.subscribe(subject);
    }

    private static NatsJetStreamSubscription genericPullSub(Connection nc) throws IOException, JetStreamApiException {
        String subject = genericSub(nc);
        JetStream js = nc.jetStream();
        return (NatsJetStreamSubscription) js.subscribe(subject, PullSubscribeOptions.DEFAULT_PULL_OPTS);
    }

    private static String genericSub(Connection nc) throws IOException, JetStreamApiException {
        String id = "-" + ID.incrementAndGet() + "-" + System.currentTimeMillis();
        String stream = STREAM + id;
        String subject = STREAM + id;
        createMemoryStream(nc, stream, subject);
        return subject;
    }

    private static NatsJetStreamSubscription mockSub(NatsConnection connection, MessageManager manager) {
        return new NatsJetStreamSubscription(mockSid(), null, null,
            connection, null /* dispatcher */,
            null /* js */,
            null, null, manager);
    }

    static class TestMessageManager extends MessageManager {
        public TestMessageManager() {
            super(null, true);
        }

        @Override
        protected boolean manage(Message msg) {
            return false;
        }

        @Override
        protected void startup(NatsJetStreamSubscription sub) {
            this.sub = sub;
        }

        @Override
        protected void shutdown() {}

        NatsJetStreamSubscription getSub() { return sub; }
    }

    @Test
    public void testMessageManagerInterfaceDefaultImplCoverage() {
        TestMessageManager tmm = new TestMessageManager();
        NatsJetStreamSubscription sub =
            new NatsJetStreamSubscription(mockSid(), "sub", null, null, null, null, "stream", "con", tmm);
        tmm.startup(sub);
        assertSame(sub, tmm.getSub());
    }
}
