/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.example.bot.spring;

import static java.util.Collections.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.google.common.io.ByteStreams;
import com.linecorp.bot.client.LineBlobClient;
import com.linecorp.bot.client.LineMessagingClient;
import com.linecorp.bot.client.MessageContentResponse;
import com.linecorp.bot.model.ReplyMessage;
import com.linecorp.bot.model.event.BeaconEvent;
import com.linecorp.bot.model.event.Event;
import com.linecorp.bot.model.event.FollowEvent;
import com.linecorp.bot.model.event.JoinEvent;
import com.linecorp.bot.model.event.MemberJoinedEvent;
import com.linecorp.bot.model.event.MemberLeftEvent;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.PostbackEvent;
import com.linecorp.bot.model.event.UnfollowEvent;
import com.linecorp.bot.model.event.UnknownEvent;
import com.linecorp.bot.model.event.UnsendEvent;
import com.linecorp.bot.model.event.VideoPlayCompleteEvent;
import com.linecorp.bot.model.event.message.AudioMessageContent;
import com.linecorp.bot.model.event.message.ContentProvider;
import com.linecorp.bot.model.event.message.FileMessageContent;
import com.linecorp.bot.model.event.message.ImageMessageContent;
import com.linecorp.bot.model.event.message.LocationMessageContent;
import com.linecorp.bot.model.event.message.StickerMessageContent;
import com.linecorp.bot.model.event.message.TextMessageContent;
import com.linecorp.bot.model.event.message.VideoMessageContent;
import com.linecorp.bot.model.event.source.Source;
import com.linecorp.bot.model.message.AudioMessage;
import com.linecorp.bot.model.message.ImageMessage;
import com.linecorp.bot.model.message.LocationMessage;
import com.linecorp.bot.model.message.Message;
import com.linecorp.bot.model.message.StickerMessage;
import com.linecorp.bot.model.message.TextMessage;
import com.linecorp.bot.model.message.VideoMessage;
import com.linecorp.bot.model.response.BotApiResponse;
import com.linecorp.bot.spring.boot.annotation.EventMapping;
import com.linecorp.bot.spring.boot.annotation.LineMessageHandler;

import aibot.HttpURLConnectionExample;
import aibot.chatBot;
import aibot.Service.LifelogService;
import aibot.Service.UserService;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@LineMessageHandler
public class KitchenSinkController {
    @Autowired
    private LineMessagingClient lineMessagingClient;

    @Autowired
    private LineBlobClient lineBlobClient;

    chatBot chat = new chatBot();

    // スタンプの制御変数
    int stamp_change = 0;

    // あいづちの制御変数
    int aizuti_change = 0;

    // 自己開示の制御変数
    int selfopen_change = 0;

    //
    int[] recognition_flag = {0,0,0};

    public void stamp_changeSet() {
    	stamp_change = 1;
    }

    public void aizuti_changeSet() {
    	aizuti_change = 1;
    }

    public void selfopenSet() {
    	selfopen_change = 1;
    }

    // Morphiaの宣言
    public Datastore getDatastore() {
    	Datastore datastore = Morphia.createDatastore( "morphia_example");
    	datastore.getMapper().mapPackage("aibot");
    	datastore.ensureIndexes();

    	return datastore;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////


    @EventMapping
    public void handleTextMessageEvent(MessageEvent<TextMessageContent> event) throws Exception {
        TextMessageContent message = event.getMessage();
        handleTextContent(event.getReplyToken(), event, message);
    }

    @EventMapping
    public void handleStickerMessageEvent(MessageEvent<StickerMessageContent> event) {
        handleSticker(event.getReplyToken(), event.getMessage());
    }

    @EventMapping
    public void handleLocationMessageEvent(MessageEvent<LocationMessageContent> event) {
        LocationMessageContent locationMessage = event.getMessage();
        reply(event.getReplyToken(), new LocationMessage(
                locationMessage.getTitle(),
                locationMessage.getAddress(),
                locationMessage.getLatitude(),
                locationMessage.getLongitude()
        ));
    }

    @EventMapping
    public void handleImageMessageEvent(MessageEvent<ImageMessageContent> event) throws IOException {
        // You need to install ImageMagick
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    final ContentProvider provider = event.getMessage().getContentProvider();
                    final DownloadedContent jpg;
                    final DownloadedContent previewImg;
                    if (provider.isExternal()) {
                        jpg = new DownloadedContent(null, provider.getOriginalContentUrl());
                        previewImg = new DownloadedContent(null, provider.getPreviewImageUrl());
                    } else {
                        jpg = saveContent("jpg", responseBody);
                        previewImg = createTempFile("jpg");
                        system(
                                "convert",
                                "-resize", "240x",
                                jpg.path.toString(),
                                previewImg.path.toString());
                    }
                    reply(event.getReplyToken(),
                          new ImageMessage(jpg.getUri(), previewImg.getUri()));
                });
    }

    @EventMapping
    public void handleAudioMessageEvent(MessageEvent<AudioMessageContent> event) throws IOException {
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    final ContentProvider provider = event.getMessage().getContentProvider();
                    final DownloadedContent mp4;
                    if (provider.isExternal()) {
                        mp4 = new DownloadedContent(null, provider.getOriginalContentUrl());
                    } else {
                        mp4 = saveContent("mp4", responseBody);
                    }
                    reply(event.getReplyToken(), new AudioMessage(mp4.getUri(), 100));
                });
    }

    @EventMapping
    public void handleVideoMessageEvent(MessageEvent<VideoMessageContent> event) throws IOException {
        log.info("Got video message: duration={}ms", event.getMessage().getDuration());

        // You need to install ffmpeg and ImageMagick.
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    final ContentProvider provider = event.getMessage().getContentProvider();
                    final DownloadedContent mp4;
                    final DownloadedContent previewImg;
                    if (provider.isExternal()) {
                        mp4 = new DownloadedContent(null, provider.getOriginalContentUrl());
                        previewImg = new DownloadedContent(null, provider.getPreviewImageUrl());
                    } else {
                        mp4 = saveContent("mp4", responseBody);
                        previewImg = createTempFile("jpg");
                        system("convert",
                               mp4.path + "[0]",
                               previewImg.path.toString());
                    }
                    String trackingId = UUID.randomUUID().toString();
                    log.info("Sending video message with trackingId={}", trackingId);
                    reply(event.getReplyToken(),
                          VideoMessage.builder()
                                      .originalContentUrl(mp4.getUri())
                                      .previewImageUrl(previewImg.uri)
                                      .trackingId(trackingId)
                                      .build());
                });
    }

    @EventMapping
    public void handleVideoPlayCompleteEvent(VideoPlayCompleteEvent event) throws IOException {
        log.info("Got video play complete: tracking id={}", event.getVideoPlayComplete().getTrackingId());
        this.replyText(event.getReplyToken(),
                       "You played " + event.getVideoPlayComplete().getTrackingId());
    }

    @EventMapping
    public void handleFileMessageEvent(MessageEvent<FileMessageContent> event) {
        this.reply(event.getReplyToken(),
                   new TextMessage(String.format("Received '%s'(%d bytes)",
                                                 event.getMessage().getFileName(),
                                                 event.getMessage().getFileSize())));
    }

    @EventMapping
    public void handleUnfollowEvent(UnfollowEvent event) {
        log.info("unfollowed this bot: {}", event);
    }

    @EventMapping
    public void handleUnknownEvent(UnknownEvent event) {
        log.info("Got an unknown event!!!!! : {}", event);
    }


    @EventMapping
    public void handleJoinEvent(JoinEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Joined " + event.getSource());
    }

    @EventMapping
    public void handlePostbackEvent(PostbackEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken,
                       "Got postback data " + event.getPostbackContent().getData() + ", param " + event
                               .getPostbackContent().getParams().toString());
    }

    @EventMapping
    public void handleBeaconEvent(BeaconEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got beacon message " + event.getBeacon().getHwid());
    }

    @EventMapping
    public void handleMemberJoined(MemberJoinedEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got memberJoined message " + event.getJoined().getMembers()
                                                                      .stream().map(Source::getUserId)
                                                                      .collect(Collectors.joining(",")));
    }

    @EventMapping
    public void handleMemberLeft(MemberLeftEvent event) {
        log.info("Got memberLeft message: {}", event.getLeft().getMembers()
                                                    .stream().map(Source::getUserId)
                                                    .collect(Collectors.joining(",")));
    }

    @EventMapping
    public void handleMemberLeft(UnsendEvent event) {
        log.info("Got unsend event: {}", event);
    }

    @EventMapping
    public void handleOtherEvent(Event event) {
        log.info("Received message(Ignored): {}", event);
    }

    private void reply(@NonNull String replyToken, @NonNull Message message) {
        reply(replyToken, singletonList(message));
    }

    private void reply(@NonNull String replyToken, @NonNull List<Message> messages) {
        reply(replyToken, messages, false);
    }

    private void reply(@NonNull String replyToken,
                       @NonNull List<Message> messages,
                       boolean notificationDisabled) {
        try {
            BotApiResponse apiResponse = lineMessagingClient
                    .replyMessage(new ReplyMessage(replyToken, messages, notificationDisabled))
                    .get();
            log.info("Sent messages: {}", apiResponse);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void replyText(@NonNull String replyToken, @NonNull String message) {
        if (replyToken.isEmpty()) {
            throw new IllegalArgumentException("replyToken must not be empty");
        }
        if (message.length() > 1000) {
            message = message.substring(0, 1000 - 2) + "……";
        }
        this.reply(replyToken, new TextMessage(message));
    }

    private void replyTexts(@NonNull String replyToken, @NonNull List<String> list2) {
        if (replyToken.isEmpty()) {
            throw new IllegalArgumentException("replyToken must not be empty");
        }
        List<Message> list = new ArrayList<Message>();
        for (int i = 0; i < list2.size(); i++) {
        	list.add(new TextMessage(list2.get(i)));
        }
        this.reply(replyToken, list);
    }

    // ボットがテキストメッセージと画像を同時に返信する
    private void replyTextImage(@NonNull String replyToken, @NonNull List<String> res_text, @NonNull List<String> res_image) {
    	if (replyToken.isEmpty()) {
            throw new IllegalArgumentException("replyToken must not be empty");
        }
        List<Message> list = new ArrayList<Message>();
        for (int i = 0; i < res_image.size(); i++) {
        	list.add(ImageMessage
                    .builder()
                    .originalContentUrl(createUri("/static/icon/"+res_image.get(i)+".png"))
                    .previewImageUrl(createUri("/static/icon/"+res_image.get(i)+".png"))
                    .build());
        }
        for (int i = 0; i < res_text.size(); i++) {
        	list.add(new TextMessage(res_text.get(i)));
        }
        this.reply(replyToken, list);
    }

    private void handleHeavyContent(String replyToken, String messageId,
                                    Consumer<MessageContentResponse> messageConsumer) {
        final MessageContentResponse response;
        try {
            response = lineBlobClient.getMessageContent(messageId)
                                     .get();
        } catch (InterruptedException | ExecutionException e) {
            reply(replyToken, new TextMessage("Cannot get image: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        messageConsumer.accept(response);
    }

    private void handleSticker(String replyToken, StickerMessageContent content) {
        reply(replyToken, new StickerMessage(
                content.getPackageId(), content.getStickerId())
        );
    }

    // フォローした際に返信するメッセージの送信
    @EventMapping
    public List<TextMessage> handleFollow(FollowEvent event) {
      List<String> followRes = new ArrayList<String>();
      List<TextMessage> res = new ArrayList<TextMessage>();
      String userId = event.getSource().getUserId();

      followRes.add("あなたのidは" + userId + "です");
      followRes.add("やあ！　おいらはオウムアウアからこの星にやってきた調査隊だ。どうか「きっくん」と呼んでくれよな。");
      followRes.add("おいらがこの星にやってきた理由は、ずばり！　君たち地球人の生態を調査するためなのさ！");

      for(int i=0; i<followRes.size(); i++) {
    	  res.add(new TextMessage(followRes.get(i)));
      }

      return res;
    }

    private void handleTextContent(String replyToken, Event event, TextMessageContent content)
            throws Exception {
        final String text = content.getText();

        log.info("Got text message from replyToken:{}: text:{} emojis:{}", replyToken, text,
                 content.getEmojis());
        switch (text) {

            ////////画像送信の追加////////////////////////////////////////////////
            case "image":
                //            final String originalContentUrl,
                //            final String previewImageUrl,

                this.reply(replyToken, ImageMessage
                        .builder()
                        .originalContentUrl(createUri("/static/icon/stamp1.png"))
                        .previewImageUrl(createUri("/static/icon/stamp1.png"))
                        .build());
                break;
            /////////////////////////////////////////////////////////////////////////////////////////////

            // 「きっくんとおしゃべりする！！」ボタンを押した際の返答
            case "きっくんとおしゃべりする":
            	List<String> talk_res = new ArrayList<String>();

            	talk_res.add("話しかけてくれてありがとう！");
            	talk_res.add("よかったら僕にキミのことを教えてほしいな！");
            	talk_res.add("最近あったこととか聞かせておくれよ");

            	this.replyTexts(replyToken, talk_res);

            	break;

            // 「悩みを聞いて！！」ボタンを押した際の返答
            case "きっくんに相談する":
            	List<String> soudan_res = new ArrayList<String>();

            	soudan_res.add("ごめんよ！");
            	soudan_res.add("まだ僕は相談を聞けるほど、地球について詳しくはないんだ");
            	soudan_res.add("だから相談するのはもう少し待ってておくれ");
            	soudan_res.add("「きっくんとおしゃべりする！！」からおしゃべりならいつでも大歓迎だよ！");

            	this.replyTexts(replyToken, soudan_res);

            	break;

            case "セルフチェックしたいな":
            	List<String> check_res = new ArrayList<String>();

            	check_res.add("この機能はまだ実装していないから待ってておくれ");
            	this.replyTexts(replyToken, check_res);

            	break;

            case "よくある質問":
            	List<String> shitumon_res = new ArrayList<String>();

            	shitumon_res.add("この機能はまだ実装していないから待ってておくれ");
            	this.replyTexts(replyToken, shitumon_res);

            	break;

            case "相談窓口につなぐ":
            	List<String> connect_res = new ArrayList<String>();

            	connect_res.add("この機能はまだ実装していないから待ってておくれ");
            	this.replyTexts(replyToken, connect_res);

            	break;



            // ここからボットの返信プログラム開始
            default:
                Datastore ds = getDatastore();
                LifelogService lifelogServ = new LifelogService();
                UserService user = new UserService();
                HttpURLConnectionExample http = new HttpURLConnectionExample();
                List<String> botQuestion = new ArrayList<String>();
                String userId = event.getSource().getUserId();
                var result = http.getResult(text);
                System.out.println(result);
                List<String> imageRes = new ArrayList<String>();

                long idNum = lifelogServ.countAll(ds,userId);

                if(result != null) {
                	var polarity = http.getPolarity(text);  //polarityの結果を取得
                	if(polarity == null) {
                		System.out.println("polarityAPIでエラーが起きてます");
                	}

                    int polarity_res = polarity.get("polarity").toString().length();
                    var polarity_result = polarity.get("polarity").toString().substring(1, polarity_res-1);




                    //////// スタンプ・あいづち・自己開示の制御  /////////////////////////////////////////////////

                    //aizuti_changeSet();    // aizuti_change = 1に
                    //stamp_changeSet();     // stamp_change = 1に
                    selfopenSet();         // selfopen_change = 1に（LifelogServiceのcheckWhom内のselfOpenも変更する必要あり）



                    ///////// あいづち返答を制御  ////////////////////////////////////////////////////////////////
                    /*
                    var aizuti_res = http.getResponse("User:" + text +",Qtype:aizuti");;
                    int aizuti_len = aizuti_res.get("question").toString().length();
                    var aizuti_result = aizuti_res.get("question").toString().substring(1, aizuti_len-1);

                    /*
                      aizuti_change = 0ならあいづち返答あり
                      aizuti_change = 1ならあいづち返答なし


                    if(aizuti_change == 0) {
                    	botQuestion.add(aizuti_result);
                    }
                    */
                    ///////// スタンプを制御  ////////////////////////////////////////////////////////////////////


                    int len_stamp = result.get("stamp").toString().length();

                    if(stamp_change == 0) {
                       String stamp = lifelogServ.stampSelect(result.get("stamp").toString().substring(1, len_stamp-1));
                       imageRes.add(stamp);
                    }



                    /*
                      stamp_change = 0ならスタンプ送信あり
                      stamp_change = 1ならスタンプ送信なし
                    */



                    ///////// 空のスロットに応じて返答を制御  //////////////////////////////////////////////////////
                    if(lifelogServ.confirmUserId(ds, userId) == 0) {

                    	lifelogServ.addLifelog(ds, text,result, userId,polarity,idNum);
                    	lifelogServ.generateLifelogAizuti(botQuestion, polarity_result, text);

                    	if(lifelogServ.checkExperiencer(ds,userId).equals("full")) {
                    		//botQuestion.add("他にも何してたか教えてほしいな！");

                    		lifelogServ.selfopenRes(botQuestion, selfopen_change, recognition_flag);
                    	}else {

                    		lifelogServ.checkExperiencer(ds, userId, botQuestion,text,recognition_flag);
                    	}

                    }else {

                    	String now_sentence = lifelogServ.getUpdateLifelog(ds, userId).getUsersSentence();
                    	if(lifelogServ.checkExperiencer(ds, userId).equals("full")) {
                    		lifelogServ.generateLifelogAizuti(botQuestion, polarity_result, text);
                        	lifelogServ.addLifelog(ds, text,result, userId, polarity,idNum);
                        	lifelogServ.checkExperiencer(ds, userId, botQuestion,text,recognition_flag);

                        }else{
                        	idNum = idNum - 1;
                        	lifelogServ.generateQuestionAizuti(botQuestion, polarity_result, text);

                        	if(lifelogServ.checkExperiencer(ds, userId).equals("No_experiencer")){
                        		lifelogServ.updateExperiencer(ds, result.get("experiencer").toString(), userId);
                        		lifelogServ.checkExperiencer(ds, userId, botQuestion,now_sentence,recognition_flag);

                        	}else if(lifelogServ.checkExperiencer(ds, userId).equals("No_when")){

                        		if(result.get("when").toString().substring(1, result.get("when").toString().length()-1).equals("NIL")) {
                        			lifelogServ.updateWhen(ds, "aextraction_faileda", userId);
                        		}else {
                        			lifelogServ.updateWhen(ds, result.get("when").toString(), userId);
                        		}

                        		lifelogServ.checkExperiencer(ds, userId, botQuestion,now_sentence,recognition_flag);

                        	}else if(lifelogServ.checkExperiencer(ds, userId).equals("No_where")){
                        		//lifelogServ.updateWhere(ds, result.get("where").toString(), userId);
                        		if(result.get("where").toString().substring(1, result.get("where").toString().length()-1).equals("NIL")) {
                        			lifelogServ.updateWhere(ds, "aextraction_faileda", userId);
                        		}else {
                        			lifelogServ.updateWhere(ds, result.get("where").toString(), userId);
                        		}
                        		lifelogServ.checkExperiencer(ds, userId, botQuestion,now_sentence,recognition_flag);

                        	}else if(lifelogServ.checkExperiencer(ds, userId).equals("No_whom")){
                        		//lifelogServ.updateWhom(ds, result.get("whom").toString(), userId);
                        		if(result.get("whom").toString().substring(1, result.get("whom").toString().length()-1).equals("NIL")) {
                        			lifelogServ.updateWhom(ds, "aextraction_faileda", userId);
                        		}else {
                        			lifelogServ.updateWhom(ds, result.get("whom").toString(), userId);
                        		}
                        		lifelogServ.checkExperiencer(ds, userId, botQuestion,now_sentence,recognition_flag);
                        	}else if(lifelogServ.checkPolarityConf(ds, userId).equals("No_polarity")) {
                        		lifelogServ.updatePolarityHow(ds,polarity_result,userId);
                        		lifelogServ.updatePolarityConf(ds, "1.00", userId);
                        		lifelogServ.checkExperiencer(ds, userId, botQuestion,now_sentence,recognition_flag);
                        	}
                        }
                    }

                }else {
                	botQuestion.add("slot_extractionAPIでエラーが発生しています");
                }
                ///////// polarityの取得  ////////////////////////////////////////////////////////////////////





                try{
                	  String br = System.getProperty("line.separator");
                	  File file = new File("src/main/resources/log/log.txt");
                	  FileWriter filewriter = new FileWriter(file,true);
                	  filewriter.write("User" + userId + ":" + text + br);
                	  for(int i = 0; i < botQuestion.size(); i++) {
                		  filewriter.write("Bot:" + botQuestion.get(i) + br);
                	  }
                	  filewriter.close();

                	}catch(IOException e){
                	  System.out.println(e);
                }
                this.replyTextImage(replyToken, botQuestion, imageRes);
                user.addlog(ds, text, botQuestion, userId,imageRes,idNum);


/*
            	for (int i = 0; i < chat_reply.length; i++) {
            		if (event instanceof MessageEvent) {
                    	newReplyToken = ((MessageEvent)event).getReplyToken();
                    }
            		log.info("Returns echo message {}: {}", newReplyToken, chat_reply[i]);
                    this.replyText(
                            newReplyToken,
                            chat_reply[i]
                    );

  	            }
*/
                break;
        }
    }

    private static URI createUri(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                                          .scheme("https")
                                          .path(path).build()
                                          .toUri();
    }

    private void system(String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        try {
            Process start = processBuilder.start();
            int i = start.waitFor();
            log.info("result: {} =>  {}", Arrays.toString(args), i);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            log.info("Interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private static DownloadedContent saveContent(String ext, MessageContentResponse responseBody) {
        log.info("Got content-type: {}", responseBody);

        DownloadedContent tempFile = createTempFile(ext);
        try (OutputStream outputStream = Files.newOutputStream(tempFile.path)) {
            ByteStreams.copy(responseBody.getStream(), outputStream);
            log.info("Saved {}: {}", ext, tempFile);
            return tempFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DownloadedContent createTempFile(String ext) {
        String fileName = LocalDateTime.now().toString() + '-' + UUID.randomUUID() + '.' + ext;
        Path tempFile = KitchenSinkApplication.downloadedContentDir.resolve(fileName);
        tempFile.toFile().deleteOnExit();
        return new DownloadedContent(
                tempFile,
                createUri("/downloaded/" + tempFile.getFileName()));
    }

    @Value
    private static class DownloadedContent {
        Path path;
        URI uri;
    }
}
