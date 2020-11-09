package npclient.gui.controller;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Callback;
import npclient.MyAccount;
import npclient.core.callback.OnAcceptListener;
import npclient.core.callback.OnPublishMessageSuccess;
import npclient.core.callback.OnRejectListener;
import npclient.core.callback.SubscribedTopicListener;
import npclient.core.command.Publisher;
import npclient.core.command.Subscriber;
import npclient.core.transferable.FileInfo;
import npclient.exception.DuplicateGroupException;
import npclient.gui.entity.*;
import npclient.gui.manager.MessageManager;
import npclient.gui.manager.MessageSubscribeManager;
import npclient.gui.util.UIUtils;
import npclient.gui.view.ChatBox;
import npclient.gui.view.ChatItemCell;
import npclient.gui.view.CircleImageView;
import npclient.gui.view.VoiceChatDialog;
import nputils.Constants;
import nputils.DataTransfer;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

public class BaseController implements Initializable {

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

    private Stage voiceChatStage;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        Callback<ListView<ChatItem>, ListCell<ChatItem>> cellFactory = new Callback<ListView<ChatItem>, ListCell<ChatItem>>() {
            @Override
            public ListCell<ChatItem> call(ListView<ChatItem> param) {
                return new ChatItemCell();
            }
        };

        lvUserItem.setCellFactory(cellFactory);
        lvGroupItem.setCellFactory(cellFactory);

        ChangeListener<ChatItem> itemChangeListener = new ChangeListener<ChatItem>() {
            @Override
            public void changed(ObservableValue<? extends ChatItem> observable, ChatItem oldChat, ChatItem newChat) {
                if (newChat != null)
                    changeChatBox(newChat.getName(), newChat instanceof GroupChatItem);
            }
        };

        lvUserItem.getSelectionModel().selectedItemProperty().addListener(itemChangeListener);
        lvGroupItem.getSelectionModel().selectedItemProperty().addListener(itemChangeListener);

        listenOnlineUsers();

        listenVoiceCall();

        final String name = MyAccount.getInstance().getName();
        this.tUsername.setText(name);
        this.civAvatar.update(name);
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
                                onReceiveVoiceAccept(message);
                                break;

                            case Constants.VOICE_REJECT:
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
                        String current = null;

                        ChatBox currentChatBox = getCurrentChat();
                        if (currentChatBox != null)
                            current = currentChatBox.getTarget();

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
     * @param username of current user
     * @param topic topic
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
     * @param topic subscribed topic
     * @param message received
     */
    private void onReceiveNewMessage(String topic, DataTransfer message) {
        Message msg = null;

        Object content = message.data;
        if (content instanceof String) {
            TextMessage textMessage = new TextMessage();
            textMessage.setContent(content.toString());
            msg = textMessage;
        } else if (content instanceof FileInfo) {
            FileMessage fileMessage = new FileMessage();
            fileMessage.setFileInfo((FileInfo) content);
            msg = fileMessage;
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

            if (isCurrentChat) {
                chatBox.setItems(messages);
                messages.setSeen(true);
            } else {
                messages.setSeen(false);
                updateChatItems(messages);
            }
        }
    }

    /**
     * Get current chatting user
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
     * @param target name
     * @param isGroup is group chat
     */
    private void changeChatBox(String target, boolean isGroup) {
        ChatBox chatBox = new ChatBox();
        chatBox.setTarget(target, isGroup);

        clearChatBox();
        paneChatBox.getChildren().add(chatBox);
    }

    private void onReceiveVoiceQuit(DataTransfer message) {
        closeVoiceChatDialog();
        MyAccount.getInstance().setInCall(false);
    }

    private void onReceiveVoiceReject(DataTransfer message) {
        MyAccount.getInstance().setInCall(false);
    }

    private void onReceiveVoiceAccept(DataTransfer message) {
        openVoiceChatDialog(message.name);
    }

    private void onReceiveVoiceRequest(DataTransfer message) {
        boolean inCall = MyAccount.getInstance().isInCall();
        final String username = MyAccount.getInstance().getName();
        final String resTopic = String.format("voice/%s", message.name);

        if (inCall) {
            new Publisher(resTopic, username)
                    .putData(Constants.VOICE_REJECT)
                    .post();
        } else {
            MyAccount.getInstance().setInCall(true);
            String content = String.format("%s is calling you. Answer?", message.name);
            UIUtils.showYesNoAlert(content, new OnAcceptListener() {
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
                                }
                            })
                            .post();
                }
            });
        }
    }

    private void openVoiceChatDialog(String target) {
//            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/voice_chat.fxml"));
//            Parent root = loader.load();
//            VoiceChatController controller = loader.getController();
//            controller.setUser1(MyAccount.getInstance().getName());
//            controller.setUser2(target);
//
//            Scene scene = new Scene(root);

        voiceChatStage = new VoiceChatDialog();
//            voiceChatStage.setTitle("Voice Chat");
//            voiceChatStage.setScene(scene);
//
//            voiceChatStage.initOwner(StageManager.getInstance().getPrimaryStage());
//
//            voiceChatStage.setOnHiding(new EventHandler<WindowEvent>() {
//                @Override
//                public void handle(WindowEvent event) {
//                    controller.stop();
//                }
//            });

        voiceChatStage.show();
    }

    private void closeVoiceChatDialog() {
        if (voiceChatStage != null) {
            voiceChatStage.close();
        }
    }

    private synchronized void updateChatItems(Messages messages) {
        ListView<ChatItem> listView = messages.isGroup() ? lvGroupItem : lvUserItem;
        String name = messages.getTopic().split(Constants.SPLITTER)[1];

        ChatItem chatItem = listView.getItems().stream()
                .filter(i -> i.getName().equals(name))
                .findFirst()
                .orElse(null);

        if (chatItem != null) {
            // update chat item info
            chatItem.update(messages);

            // swap to first
            listView.getItems().remove(chatItem);
            listView.getItems().add(0, chatItem);
        }
    }

    public synchronized void join(String group) throws DuplicateGroupException {
        String topic = String.format("group/%s", group);
        if (!MessageSubscribeManager.getInstance().containsKey(topic)) {
            ChatItem item = new GroupChatItem();
            item.setName(group);
            item.setSeen(true);

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
        } catch (DuplicateGroupException e) {
            UIUtils.showErrorAlert(e.getMessage());
        }
    }
}
