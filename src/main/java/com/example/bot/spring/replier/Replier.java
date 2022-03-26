package com.example.bot.spring.replier;

import com.linecorp.bot.model.message.Message;

//返信用クラスのためのインターフェース
public interface Replier {

// 返信用クラスが必ず実装するメソッド
Message reply();

}
