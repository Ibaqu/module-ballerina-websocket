// Copyright (c) 2020 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/io;

listener Listener l16 = check new(21013);
service UpgradeService /'error/ws on l16 {
   resource isolated function get .() returns Service|UpgradeError {
       return new ErrorService();
   }
}

service class ErrorService {
   *Service;
   remote function onConnect(Caller ep) {
   }

   remote function onString(Caller ep, string text) {
       var returnVal = ep->writeString(text);
       if (returnVal is Error) {
           panic <error>returnVal;
       }
   }

   remote function onError(Caller ep, error err) {
       io:println(err.message());
   }

   remote function onClose(Caller ep, int statusCode, string reason) {
   }
}
