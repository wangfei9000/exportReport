package com.wf

import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.serializer.SerializerFeature

class SM4 {

    static String http(String url, String parameters)
    {
        def connection = new URL(url).openConnection() as HttpURLConnection
        connection.setDoOutput(true)
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Charset", "utf-8")
        connection.setRequestProperty("Content-Type", "application/json")
        def output = connection.getOutputStream()
        output.write(parameters.getBytes())
        output.flush()
        def result = new String(connection.getInputStream().bytes, "utf-8")
        return result
    }

/*
{"address":"淳安县千岛湖镇浅山花园7幢905室","roomNumber":"905室","districtName":"淳安县","totalPrice":"807530","houseType":"住宅","externalId":"7a2b5004-73bc-49f8-8dc5-044596e6e6be","buildYear":2015,"building":"7幢","communityExternalId":"1eddd132-bf50-11e5-8d9b-008cfae40b58","priceDate":"2025-11-26","province":"浙江省","cityName":"杭州市","price":9988,"communityName":"浅山花园","communityId":1427647,"status":"OK"}
{"address":"淳安县千岛湖镇浅山花园7幢905室","roomNumber":null,"districtName":null,"totalPrice":806721,"houseType":null,"externalId":null,"buildYear":null,"building":null,"priceDate":"2025-11-26","province":"","cityName":"杭州市","price":9978,"communityName":"","status":"已评估"}

* */
    static void callInterface(String address,String area){
        def parameter = """ 
{
    "area": ${area},
    "mode":"自动",
    "address":"${address}",
    "cityZipCode":"330100"
}
        """
        def key = "YpDatataihangque"
        def param = cn.hutool.crypto.SmUtil.sm4(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).encryptBase64(parameter, java.nio.charset.StandardCharsets.UTF_8)
        def res = http("https://dpi.yunfangdata.com/dpi/execute?code=i3OjKag0AzsEPamP&accountExternalId=3085Q7gMFVnpGgtP",'{"param":"'+param+'"}')
        def data = JSONObject.parseObject(res).get("param")
        res = cn.hutool.crypto.SmUtil.sm4(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).decryptStr(data, java.nio.charset.StandardCharsets.UTF_8)
        //println res
        def res1 = JSONObject.parseObject(res)

        res = http("https://api.jiahuayes.com/dpi/execute?code=2159B7F02CC849BEA88A13D759DF7FF6&accountExternalId=01F9F23428F24E908093DBB7400B5258",'{"param":"'+param+'"}')
        data = JSONObject.parseObject(res).get("param")
        res = cn.hutool.crypto.SmUtil.sm4(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).decryptStr(data, java.nio.charset.StandardCharsets.UTF_8)
        //println res
        def res2 = JSONObject.parseObject(res)

        println res1.get("price")+"\t"+res1.get("totalPrice")+"\t"+res2.get("price")+"\t"+res2.get("totalPrice")


    }


    static void main(String[] args) {
/*
        def excel = """
淳安县千岛湖镇浅山花园7幢905室\t80.85
余杭区瓶窑镇凤溪路211号1幢1单元401室\t122.06
富阳区富春街道金苑路660号小墅公寓2号1801室\t104.52
萧山区盈丰街道佳丰北苑21幢1单元1502室\t129.84
桐庐县城南街道滨江路358号富春望名筑3幢1单元2001室\t169.23
上城区时光悦酩公寓13幢1单元1002室\t139.36
西湖区兰韵天城10幢1单元1102室\t122.38
之江路198号新西湖花园别墅39幢\t366.98
杭州市余杭区星桥街道金橡臻园书轩9-4室\t210.7
杭州经济技术开发区观澜时代国际花园天筑10幢104室\t299.49
余杭区余杭街道金成白云深处别墅竹翠轩2-01\t246.37
"""


        excel.eachLine {
            def c = it.split("\t")
            if(c[0] != ""){
                callInterface(c[0],c[1])
            }
        }
        //callInterface("淳安县千岛湖镇浅山花园7幢905室","80.85")
*/

        String key ="2b546becee09087b48e5a15a1654e775"
        String parameter = """
{
 "accountExternalId":"358f5eb811074b81998b10561af618b2",
 "housepType":"01",
 "buildMarea": 72.56,
 "cityName":"伊犁哈萨克自治州",
 "addres":"伊宁市阿依东街69号1单元5层109室",
 "plvlAdmDiviCd": "650000",
 "cityCd": "654000",
 "areaCountyCode": "654002",
 "assessMode":"01",
 "assessNo":"",
 "commtyName":"阿依东街69号1单元5层109室",
 "housNo":"1单元5层109",
 "houseBuildDt":"2000-11-20",
 "buildName":"1单元5层109室",
 "houseTowardsCode":"01",
 "belongFlNo":"5",
 "lngVal":"",
 "mnlValRsnDesc":"",
 "taxFeeTpCd":"",
 "houseUnitNo":"1单元5层109室"
}
"""

        byte[] bytes = utils.crypto.sm.SM4Utils.encrypt(parameter.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                org.bouncycastle.pqc.math.linearalgebra.ByteUtils.fromHexString(key))
        String param = org.bouncycastle.pqc.math.linearalgebra.ByteUtils.toHexString(bytes)
        println param
        def res = http("http://180.184.51.155:8080/dpi/execute?code=8TWnI8LKw92RXuhJ&accountExternalId=358f5eb811074b81998b10561af618b2",param)
        println res
        def decrypt = utils.crypto.sm.SM4Utils.decrypt(org.bouncycastle.pqc.math.linearalgebra.ByteUtils.fromHexString(res),
                org.bouncycastle.pqc.math.linearalgebra.ByteUtils.fromHexString(key))
        println new String(decrypt)
        //def data = JSONObject.parseObject(res).get("param")

        /*
               byte[] bytes = utils.crypto.sm.SM4Utils.encrypt(parameter.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                       org.bouncycastle.pqc.math.linearalgebra.ByteUtils.fromHexString(key))
               String result = org.bouncycastle.pqc.math.linearalgebra.ByteUtils.toHexString(bytes)
               println result

               result = """
       6f8f881d8028816d44c59fd74f9280a8b0d56e0de324531fcc229498c2e8c6f8d53c62b1237df2726e349fa8407487e9bc3713a52462b473dad8ad93bd02c44ee2ea5c5396c0e36f73fb037437b27b6d929d84f29297d1dbb3ba14aae7e7f8b4bd8e86dc520d406613b3ef905cb32011
               """
               def decrypt = utils.crypto.sm.SM4Utils.decrypt(org.bouncycastle.pqc.math.linearalgebra.ByteUtils.fromHexString(result),
                       org.bouncycastle.pqc.math.linearalgebra.ByteUtils.fromHexString(key))
               println new String(decrypt)
       */

        //def data = null
        //com.alibaba.fastjson.JSON.toJSONString(['code': '000000', 'message': '成功', 'data': data], com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue)



    }



}
