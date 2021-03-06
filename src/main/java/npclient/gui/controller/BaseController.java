package npclient.gui.controller;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import javafx.util.Callback;
import npclient.MyAccount;
import npclient.core.callback.OnAcceptListener;
import npclient.core.callback.OnPublishMessageSuccess;
import npclient.core.callback.OnRejectListener;
import npclient.core.callback.SubscribedTopicListener;
import npclient.core.command.Publisher;
import npclient.core.command.Subscriber;
import npclient.exception.InvalidNameException;
import npclient.gui.audio.NotiAudio;
import npclient.gui.manager.StageManager;
import npclient.gui.util.AudioUtils;
import npclient.gui.util.JFXSmoothScroll;
import npclient.gui.view.*;
import nputils.*;
import npclient.exception.DuplicateGroupException;
import npclient.gui.entity.*;
import npclient.gui.manager.MessageManager;
import npclient.gui.manager.MessageSubscribeManager;
import npclient.gui.util.UIUtils;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

public class BaseController implements Initializable {

    @FXML
    private CallingView paneCalling;
    @FXML
    private TextField tfGroup;
    @FXML
    private ListView<ChatItem> lvGroupItem;
    @FXML
    private Text tUsername;
    @FXML
    private CircleImageView civAvatar;
    @FXML
    private AnchorPane paneChatBox;
    @FXML
    private ListView<ChatItem> lvUserItem;

    private VoiceChatDialog voiceChatStage;

    private SimpleBooleanProperty callableProperty;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        StageManager.getInstance().setBaseController(this);

        callableProperty = new SimpleBooleanProperty(true);

        initializeListView(lvUserItem);
        initializeListView(lvGroupItem);

        listenOnlineUsers();
        listenVoiceCall();

        final String name = MyAccount.getInstance().getName();
        this.tUsername.setText(name);
        this.civAvatar.update(name);
    }

    private void initializeListView(ListView<ChatItem> listView) {
        listView.setCellFactory(new Callback<ListView<ChatItem>, ListCell<ChatItem>>() {
            @Override
            public ListCell<ChatItem> call(ListView<ChatItem> param) {
                return new ChatItemCell();
            }
        });

        listView.setOnMouseClicked(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                ChatItem selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    changeChatBox(selected);
                }
            }
        });

        JFXSmoothScroll.smoothScrollingListView(listView, 1f);
    }

    /**
     * Listen to voice call signal
     */
    private void listenVoiceCall() {
        final String username = MyAccount.getInstance().getName();
        final String topic = String.format("voice/%s", username);
        new Subscriber(topic, username)
                .setNewMessageListener(new SubscribedTopicListener() {
                    @Override
                    public void onReceive(DataTransfer message) {
                        String action = message.data.toString();
                        switch (action) {
                            case Constants.VOICE_REQUEST:
                                onReceiveVoiceRequest(message);
                                break;

                            case Constants.VOICE_ACCEPT:
                                hideCallingPane();
                                onReceiveVoiceAccept(message);
                                break;

                            case Constants.VOICE_REJECT:
                                hideCallingPane();
                                onReceiveVoiceReject(message);
                                break;

                            case Constants.VOICE_QUIT:
                                onReceiveVoiceQuit(message);

                        }
                    }
                })
                .listen();
    }

    /**
     * Listen to online users
     */
    private void listenOnlineUsers() {
        final String username = MyAccount.getInstance().getName();
        new Subscriber(Constants.ONLINE_TOPIC, username)
                .setNewMessageListener(new SubscribedTopicListener() {
                    @Override
                    public void onReceive(DataTransfer message) {
                        // Get current user in chat box
                        ChatBox currentChatBox = getCurrentChat();
                        final String current = currentChatBox != null ? currentChatBox.getTarget() : null;

                        boolean isCurrentOnline = current == null;

                        // Clear all exist chat item
                        lvUserItem.getItems().clear();

                        // Retrieve online users
                        List<String> onlineUsers = (List<String>) message.data;

                        // Clear all offline user chat messages in MessageManager
                        MessageManager.getInstance().clearOffline(onlineUsers);
                        MessageSubscribeManager.getInstance().clearOffline(onlineUsers);

                        for (String user : onlineUsers) {
                            // Add user (not your self) into listview
                            if (!username.equals(user)) {
                                ChatItem item = new UserChatItem();
                                item.setName(user);
                                lvUserItem.getItems().add(item);

                                // Check whether current user still online
                                if (user.equals(current))
                                    isCurrentOnline = true;
                                else {
                                    String topic = String.format("chat/%s", user);
                                    if (!MessageSubscribeManager.getInstance().containsKey(topic)) {
                                        // with other user listen message
                                        Subscriber subscriber = subscribeMessages(username, topic);
                                        MessageSubscribeManager.getInstance().put(topic, subscriber);
                                    }
                                }
                            }
                        }

                        // In case current user offline
                        // Clear chat box
                        if (!isCurrentOnline) {
                            clearChatBox();
                        }
                    }
                })
                .listen();
    }

    /**
     * Generate subscriber subscribe listen to message from a user
     *
     * @param username of current user
     * @param topic    topic
     * @return subscriber
     */
    private Subscriber subscribeMessages(String username, String topic) {
        Subscriber subscriber = new Subscriber(topic, username)
                .setNewMessageListener(new SubscribedTopicListener() {
                    @Override
                    public void onReceive(DataTransfer message) {
                        onReceiveNewMessage(topic, message);
                    }
                });
        subscriber.listen();
        return subscriber;
    }

    /**
     * Callback fires when receive new message from user
     *
     * @param topic   subscribed topic
     * @param message received
     */
    private void onReceiveNewMessage(String topic, DataTransfer message) {
        Message msg = null;

        NotiAudio notiAudio = new NotiAudio();
        notiAudio.start();

        Object content = message.data;
        if (content instanceof String) {
            TextMessage textMessage = new TextMessage();
            textMessage.setContent(content.toString());
            msg = textMessage;
        } else if (content instanceof FileInfo) {
            FileMessage fileMessage = new FileMessage();
            fileMessage.setContent((FileInfo) content);
            msg = fileMessage;
        } else if (content instanceof Emoji) {
            EmojiMessage emojiMessage = new EmojiMessage();
            emojiMessage.setContent((Emoji) content);
            msg = emojiMessage;
        }

        if (msg != null) {
            msg.setFrom(message.name);
            msg.setTime(message.datetime);

            Messages messages = MessageManager.getInstance().append(topic, msg);
            boolean isGroup = messages.isGroup();

            boolean isCurrentChat = false;
            ChatBox chatBox = getCurrentChat();
            if (chatBox != null) {
                String current = chatBox.getTarget();
                if ((!isGroup && message.name.equals(current))
                        || (isGroup && topic.equals(String.format("group/%s", current)))) {
                    isCurrentChat = true;
                }
            }

            ChatItem chatItem = getChatItemByMessages(messages);
            if (chatItem != null) {
                chatItem.setSeen(isCurrentChat);
            }

            if (isCurrentChat) {
                chatBox.addItem(msg);
            }

            updateChatItems(messages);
        }
    }

    /**
     * Get current chatting user
     *
     * @return current chatting username
     */
    private ChatBox getCurrentChat() {
        if (!paneChatBox.getChildren().isEmpty()) {
            Node first = paneChatBox.getChildren().get(0);
            if (first instanceof ChatBox) {
                return (ChatBox) first;
            }
        }

        return null;
    }

    /**
     * Clear chat box section
     */
    private void clearChatBox() {
        paneChatBox.getChildren().clear();
    }

    /**
     * Change chat box section by username
     *
     * @param newChat chat item
     */
    private void changeChatBox(ChatItem newChat) {
        String target = newChat.getName();
        boolean isGroup = newChat instanceof GroupChatItem;

        newChat.setSeen(true);
        newChat.getCell().updateItem(newChat);

        ChatBox prevChatBox = getCurrentChat();
        // if reselect a target, do nothing
        if (prevChatBox != null && prevChatBox.getTarget().equals(target))
            return;

        ChatBox chatBox = new ChatBox();
        chatBox.setOnSendListener(new ChatBoxController.OnSendListener() {
            @Override
            public void onSend(Messages messages) {
                if (messages.getChatItem() == null)
                    messages.setChatItem(newChat);
                updateChatItems(messages);
            }
        });
        chatBox.setTarget(target, isGroup);

        clearChatBox();
        paneChatBox.getChildren().add(chatBox);
    }

    private void onReceiveVoiceQuit(DataTransfer message) {
        MyAccount.getInstance().setInCall(false);
        callableProperty.set(true);
        closeVoiceChatDialog();
        UIUtils.showSimpleAlert(Alert.AlertType.INFORMATION, "Called end.");
    }

    private void onReceiveVoiceReject(DataTransfer message) {
        MyAccount.getInstance().setInCall(false);
        callableProperty.set(true);
        String content = String.format("%s rejected your call request.", message.name);
        UIUtils.showSimpleAlert(Alert.AlertType.INFORMATION, content);
    }

    private void onReceiveVoiceAccept(DataTransfer message) {
        openVoiceChatDialog(message.name);
    }

    private void onReceiveVoiceRequest(DataTransfer message) {
        boolean inCall = MyAccount.getInstance().isInCall();
        final String username = MyAccount.getInstance().getName();
        final String resTopic = String.format("voice/%s", message.name);

        if (inCall || !AudioUtils.isVoiceChatSupported()) {
            new Publisher(resTopic, username)
                    .putData(Constants.VOICE_REJECT)
                    .post();
        } else {
            MyAccount.getInstance().setInCall(true);
            callableProperty.set(false);
            UIUtils.showIncomingCallAlert(message.name, new OnAcceptListener() {
                @Override
                public void onAccept() {
                    new Publisher(resTopic, username)
                            .putData(Constants.VOICE_ACCEPT)
                            .setSuccessListener(new OnPublishMessageSuccess() {
                                @Override
                                public void onReceive(DataTransfer m) {
                                    openVoiceChatDialog(message.name);
                                }
                            })
                            .post();
                }
            }, new OnRejectListener() {
                @Override
                public void onReject() {
                    new Publisher(resTopic, username)
                            .putData(Constants.VOICE_REJECT)
                            .setSuccessListener(new OnPublishMessageSuccess() {
                                @Override
                                public void onReceive(DataTransfer message) {
                                    MyAccount.getInstance().setInCall(false);
                                    callableProperty.set(true);
                                }
                            })
                            .post();
                }
            });
        }
    }

    public void setCallablePropertyValue(boolean callable) {
        this.callableProperty.set(callable);
    }

    public SimpleBooleanProperty callableProperty() {
        return callableProperty;
    }

    public void showCallingPane(String name) {
//        if (!paneCalling.getChildren().isEmpty()) {
//            Node node = paneCalling.getChildren().get(0);
//            if (node instanceof Text) {
//                ((Text) node).setText(String.format("You are calling %s", name));
//            }
//        }
//        paneCalling.setVisible(true);
        paneCalling.show(name);
    }

    public void hideCallingPane() {
//        paneCalling.setVisible(false);
        paneCalling.hide();
    }

    private void openVoiceChatDialog(String target) {
        try {
            voiceChatStage = new VoiceChatDialog();
            voiceChatStage.setUsername(MyAccount.getInstance().getName());
            voiceChatStage.setTarget(target);
            voiceChatStage.show();
        } catch (LineUnavailableException | IOException e) {
            // Quit if catch a exception
            final String topic = "voice/" + target;
            final String username = MyAccount.getInstance().getName();
            new Publisher(topic, username)
                    .putData(Constants.VOICE_QUIT)
                    .setSuccessListener(new OnPublishMessageSuccess() {
                        @Override
                        public void onReceive(DataTransfer message) {
                            closeVoiceChatDialog();
                        }
                    })
                    .post();
            UIUtils.showErrorAlert("System not support voice chat: " + e.getMessage());
        }
    }

    private void closeVoiceChatDialog() {
        if (voiceChatStage != null) {
            voiceChatStage.close();
        }
    }

    private synchronized void updateChatItems(Messages messages) {
        ChatItem chatItem = getChatItemByMessages(messages);

        if (chatItem != null) {
            // update chat item info
            chatItem.update(messages);
            chatItem.getCell().updateItem(chatItem);
        }
    }

    private ChatItem getChatItemByName(String name, boolean isGroup) {
        ListView<ChatItem> listView = isGroup ? lvGroupItem : lvUserItem;

        return listView.getItems().stream()
                .filter(i -> i.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private ChatItem getChatItemByMessages(Messages messages) {
        ChatItem chatItem = messages.getChatItem();

        if (chatItem == null) {
            boolean isGroup = messages.isGroup();
            String name = messages.getTopic().split(Constants.SPLITTER)[1];
            chatItem = getChatItemByName(name, isGroup);
        }

        return chatItem;
    }

    public synchronized void leave(String group) {
        String topic = String.format("group/%s", group);
        Subscriber subscriber = MessageSubscribeManager.getInstance().remove(topic);
        if (subscriber != null)
            subscriber.cancel();
        Messages messages = MessageManager.getInstance().get(topic);
        ChatItem chatItem;
        if (messages == null)
            chatItem = getChatItemByName(group, true);
        else
            chatItem = getChatItemByMessages(messages);

        lvGroupItem.getItems().remove(chatItem);

        // clear current chat box
        boolean isCurrentChat = false;
        ChatBox currentChatBox = getCurrentChat();
        if (currentChatBox != null) {
            isCurrentChat = currentChatBox.getTarget().equals(group);
        }
        if (isCurrentChat) {
            clearChatBox();
        }
    }

    public synchronized void join(String group) throws DuplicateGroupException, InvalidNameException {
        if (UIUtils.isInvalid(group))
            throw new InvalidNameException(group);

        String topic = String.format("group/%s", group);
        if (!MessageSubscribeManager.getInstance().containsKey(topic)) {
            ChatItem item = new GroupChatItem();
            item.setName(group);

            if (!lvGroupItem.getItems().contains(item)) {
                lvGroupItem.getItems().add(item);
            }

            // with other user listen message
            final String username = MyAccount.getInstance().getName();
            Subscriber subscriber = subscribeMessages(username, topic);
            MessageSubscribeManager.getInstance().put(topic, subscriber);

        } else
            throw new DuplicateGroupException(group);
    }

    public void joinGroup(ActionEvent actionEvent) {
        String group = tfGroup.getText().trim();
        try {
            join(group);
        } catch (DuplicateGroupException | InvalidNameException e) {
            UIUtils.showErrorAlert(e.getMessage());
        } finally {
            tfGroup.clear();
        }
    }
}
