/*
 * Copyright (C) 2011 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android;

/**
 * 枚举，调用的方式
 */
enum IntentSource {

  NATIVE_APP_INTENT,   // 三方app调用扫描，模式
  PRODUCT_SEARCH_LINK,   // 中间这两个是，查询商品的url，是拼接在查询网站后面 ，通过网络进行查询的模式
  ZXING_LINK,
  NONE                 // 直接打开扫描，模式

}
