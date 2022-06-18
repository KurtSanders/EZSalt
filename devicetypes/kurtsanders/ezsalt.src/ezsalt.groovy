import java.text.SimpleDateFormat

metadata {
    definition (name: "EZSalt Tank Device", namespace: "sandeke", author: "Kurt Sanders") {
        capability "Initialize"
        capability "Actuator"
        capability "Consumable" // consumableStatus - ENUM ["missing", "order", "maintenance_required", "good", "replace"]

        command "Disconnect"

        attribute "distance", "number"
        attribute "SaltLevelPct", "number"
        attribute "SaltTile", "string"
        attribute "NotificationPCT", "number"
        attribute "LastMessage", "string"
    }

    preferences {
        input name: "macaddress", type: "text", title: "EZSalt Mac Address (xx:xx:xx:xx:xx:xx):", required: true, displayDuringSetup: true
        input name: "salttankheightin", type: "number", title: "Salt Tank Height (inches):", required: true, displayDuringSetup: true, defaultValue: "30"
        input name: "saltlevellowin", type: "number", title: "Salt Level Notification Height (inches):", required: true, displayDuringSetup: true, defaultValue: "15"
        input name: "RunTime", type: "time", title: "When to Run (Time)", required: true, displayDuringSetup: true, defaultValue: "18:00"
        input name: "QOS", type: "text", title: "QOS Value:", required: false, defaultValue: "1", displayDuringSetup: true
        input name: "retained", type: "bool", title: "Retain message:", required: false, defaultValue: false, displayDuringSetup: true
        input("logEnable", "bool", title: "Enable logging", required: true, defaultValue: true)
    }

}

def installed() {
    log.info "Installed EZSalt Tank Device..."
    state.mqttURL = "tcp://mqtt.ezsalt.xyz:1883"
}

def updated() {
    if (logEnable) log.info "Updated Prefernces..."
    state.NotificationPCT = Math.round(saltlevellowin / salttankheightin * 100)
    state.clientID = device.deviceNetworkId[-8..-1].trim()
    cronSchedule()
    initialize()
}

def Disconnect() {
    log.info "Disconnecting from ${mqttURL()}"
    interfaces.mqtt.disconnect()
    log.info "Removed Daily Running Schedule"
    unschedule()
}

def uninstalled() {
    Disconnect()
    unschedule()
}


def cronSchedule() {
    def hour = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", RunTime).format("HH")
    def min = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", RunTime).format("mm")
    log.debug "Schedule set for reading salt level at ${hour}:${min} every day"
    schedule("0 ${min} ${hour} * * ?", "initialize")
}

def initialize() {
    state.mqttURL = "tcp://mqtt.ezsalt.xyz:1883"
    if (logEnable) runIn(900,logsOff)
    String[] macaddressParts = macaddress.split(":")
    state.topicSub = "tele/EZsalt_${macaddressParts[3]+macaddressParts[4]+macaddressParts[5]}/SENSOR"
    sendEvent(name: "NotificationPCT", value: state.NotificationPCT)
    mqttConnect()
}

def mqttConnect() {
    try {
        if(settings?.retained==null) settings?.retained=false
        if(settings?.QOS==null) setting?.QOS="1"
        interfaces.mqtt.connect(state.mqttURL,"client-hubitat",null,null)
        pauseExecution(1000)
        if (interfaces.mqtt.isConnected()) {
            log.debug "Successfully connected to ${state.mqttURL}"
            interfaces.mqtt.subscribe(state.topicSub)
            log.debug "Subscribed to: ${state.topicSub}"
        } else {
            log.debug "mqttConnect(): Initialize error connecting to ${state.mqttURL}"
            uninstalled()
        }
    } catch(e) {
        log.debug "mqttConnect(): Initialize error: ${e.message}"
        uninstalled()
    }
    pauseExecution(1000)
    interfaces.mqtt.disconnect()
}

def parse(String description) {
    Date date = new Date();
    topic = interfaces.mqtt.parseMessage(description).topic
    topic = topic.substring(topic.lastIndexOf("/") + 1)
    payload = interfaces.mqtt.parseMessage(description).payload
    int distance = Math.round(new groovy.json.JsonSlurper().parseText(payload).VL53L0X.Distance / 25.4)
    int SaltLevelPct = ((salttankheightin - distance) / salttankheightin * 100).toInteger()

    switch(SaltLevelPct) {
        case 0..25:
        img = "salt-empty.png"
        break
        case 26..50:
        img = "salt-half.png"
        break
        case 51..100:
        img = "salt-full.png"
        break
        default:
            img = "Attention.svg"
        break
    }

    if (distance > state.NotificationPCT) {
        consumableStatusValue = "good"
    } else {
        consumableStatusValue = "order"
    }

    state."${topic}" = "${payload}"
    state.lastpayloadreceived = "Topic: ${topic} : ${payload} @ ${date.toString()}"

    sendEvent(name: "LastMessage", value: "${payload} @ ${date.toString()}", displayed: true)
    sendEvent(name: "distance", value: distance, displayed: true)
    sendEvent(name: "consumableStatus", value: consumableStatusValue, displayed: true)
    sendEvent(name: "SaltLevelPct", value: SaltLevelPct, displayed: true)

    img = "https://raw.githubusercontent.com/PrayerfulDrop/Hubitat/master/support/images/${img}"
    html = "<style>img.salttankImage { max-width:80%;height:auto;}div#salttankImgWrapper {width=100%}div#salttankWrapper {font-size:13px;margin: 30px auto; text-align:center;}</style><div id='salttankWrapper'>"
    html += "<div id='salttankImgWrapper'><center><img src='${img}' class='saltankImage'></center></div>"
    html += "Salt Level: ${SaltLevelPct}%</div>"
    sendEvent(name: "SaltTile", value: html, displayed: true)

    if (logEnable) {
        log.debug "topic: ${topic}"
        log.debug "payload: ${payload}"
        log.debug "distance = ${distance} in"
        log.debug "Consumables: ${consumableStatusValue}"
        log.debug "Tank Fixed Height: ${salttankheightin} Salt Level Pct: ${SaltLevelPct} Notify: ${state.NotificationPCT}%"
    }
}


def mqttClientStatus(String status) {
    if(!status.contains("succeeded")) {
        try { interfaces.mqtt.disconnect() }
        catch (e) { }

        if(logEnable) log.debug "Broker: ${status} Will restart in 5 seconds"
        runIn (5,mqttConnect)
    }
}

def logsOff(){
    log.warn "Debug logging disabled."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}
