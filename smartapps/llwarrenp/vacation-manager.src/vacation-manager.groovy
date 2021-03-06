/*
 *  Vacation Manager
 *
 *  Copyright 2018 Warren Poschman
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

def appVersion() {
	return "1.3"
}

/*
* Change Log:
* 2018-7-17 - (1.3) Improved vacation return logic and added app state tracking for better reliability
* 2018-7-12 - (1.2) Reinstated push messages for house sitter arrival/departure and made small tweaks
* 2018-7-10 - (1.1) Improved vacation mode handling, improved reliability due to latent SHM mode status
* 2018-7-9  - (1.0) Initial release
* 2018-7-8  - (0.1) Debug release
*/

definition(
    name: "Vacation Manager",
    namespace: "LLWarrenP",
    author: "Warren Poschman",
    description: "Automatically go into vacation mode after everyone has been gone for a set time, turn off devices, and manage a house sitter",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/SafetyAndSecurity/App-NeighborhoodNetwork.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/SafetyAndSecurity/App-NeighborhoodNetwork@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/SafetyAndSecurity/App-NeighborhoodNetwork@2x.png"
)

preferences {
    page(name: "configure")
}

def configure() {
	dynamicPage(name: "configure", title: "Vacation Manager v${appVersion()}", install: true, uninstall: true) {
		def actions = location.helloHome?.getPhrases()*.label
    	actions.sort()
		section("When all of these people leave home on vacation:") {
        	paragraph "Select everyone who will be away for an extended period of time except a house sitter, dog walker, plant waterer, etc."
			input "people", "capability.presenceSensor", multiple: true, required: true
		}
		section("Change to this mode for vacation") {
        	paragraph "Select the mode to change to and how long before changing to that mode and (optionally) executing a routine.  Set the time to a duration that is longer than you would normally be gone (e.g. 18 hours)"
			input "vacationMode", "mode", title: "Which mode?", required: true
			input "vacationTime", "decimal", title: "After how many hours?", defaultValue: 24, range: "0..*", required: true
        	input "vacationRoutine", "enum", title: "And execute this routine:", required: false, options: actions
            paragraph "Note that the (optional) routine specified may also perform some duplicate actions so you can decide whether to have the routine perform them (such as HVAC) or have them done in both places to be doubly sure (such as lights and valves).  This routine will be executed when you go on vacation as well as when your house sitter departs."
		}
		section("Turn off these switches and valves while on vacation:") {
        	paragraph "Turn off these devices/valves when departing.  This can be in lieu of or a supplement to any routine executed above"
			input "offDevices", "capability.switch", title: "Which switches?", multiple: true, required: false
			input "offValves", "capability.valve", title: "Which valves?", multiple: true, required: false
        	input "boolDevicesReturn", "bool", title: "Turn them back on when I return?", required: false
		}
		section("House sitter settings while on vacation:") {
        	paragraph "If you're having someone to come by occasionally to do something while you're away, turn on the minimal required devices while they are present (i.e. water, gas, etc.)."
            paragraph "Optionally execute a routine to 'clean up' by turning off lights, locking doors, etc. such as the 'Goodbye!' routine.  After departure and any routines execute the mode will return to the above selected vacation routine and mode."
			input "houseSitters", "capability.presenceSensor", title: "Which presence sensors?", multiple: true, required: false
        	input "sitterArrivalRoutine", "enum", title: "Execute this arrival routine:", required: false, options: actions
			input "onSitterDevices", "capability.switch", title: "Turn on these switches when the sitter arrives:", multiple: true, required: false
       		input "onSitterValves", "capability.valve", title: "Open these valves when the sitter arrives:", multiple: true, required: false
        	input "boolOffSitterDevicesLeave", "bool", title: "Turn them off when they leave?", required: false
        	input "sitterDepartureRoutine", "enum", title: "Execute this departure routine:", required: false, options: actions
			input "offSitterDevices", "capability.switch", title: "Turn off these switches after the sitter leaves:", multiple: true, required: false       
			input "offSitterValves", "capability.valve", title: "Close these valves after the sitter leaves:", multiple: true, required: false       
		}
		section( "Notifications" ) {
			input("recipients", "contact", title: "Send notifications to", required: false) {
				input "sendPushMessage", "enum", title: "Send a push notification?", options: ["Yes", "No"], required: false
				input "phone", "phone", title: "Send a Text Message?", required: false
			}
		}
	}
}

def installed() {
	log.debug "Installed with settings: ${settings}"
	subscribe(people, "presence", presence)
	// Assume we are not on vacation but then check just to be sure
	state[onVacation()] = "false"
    if (everyoneIsAway()) checkVacation()
    if ((location.mode == vacationMode) && (houseSitters)) subscribe(houseSitters, "presence", houseSitterPresence) 
}

def updated() {
	log.debug "Updated with settings: ${settings}"
	unsubscribe()
    // After update, check vacation status and set state machine
    if (location.mode == vacationMode) state[onVacation()] = "true"
    else state[onVacation()] = "false"
    if (everyoneIsAway() && (state[onVacation()] != "true")) checkVacation()
	subscribe(people, "presence", presence)
    if ((location.mode == vacationMode) && (houseSitters)) subscribe(houseSitters, "presence", houseSitterPresence) 
}

def presence(evt) {
	log.debug "vacation manager was informed that someone's presence has changed to '${evt.value}'"
	if (evt.value == "not present") {
		if (everyoneIsAway()) {
			log.debug "vacation manager has determined that everyone is away; automatically enabling vacation mode ${vacationTime} hours from now if no one returns"
			runIn(findVacationThreshold() * 3600, checkVacation)
		}
	}
	else {
    	// Vacation is over as we have returned - return to normal operation (SHM will change state)
		if (state[onVacation()] == "true") log.debug "vacation manager has determined that someone has returned from vacation; exiting vacation mode"
        else log.debug "vacation manager has determined that someone has returned while not in vacation mode; resuming normal operation"
        unschedule(checkVacation)
        if (houseSitters) unsubscribe(houseSitters)
        if ((state[onVacation()] == "true") && (boolDevicesReturn)) {
        	if (offDevices) offDevices.on()
            if (offValves) offValves.open()
        }
        state[onVacation()] = "false"
    }
}

def checkVacation() {
	if (everyoneIsAway()) {
		def threshold = 1000 * 3600 * findVacationThreshold() - 1000
		def awayLongEnough = people.findAll { person ->
			def presenceState = person.currentState("presence")
			if (!presenceState) {
				// This device has yet to check in and has no presence state, treat it as not away long enough
				return false
			}
			def elapsed = now() - presenceState.rawDateCreated.time
			elapsed >= threshold
		}
		log.debug "vacationmanager found ${awayLongEnough.size()} out of ${people.size()} person(s) who were away long enough"
		if (awayLongEnough.size() == people.size()) {
			def message = "Vacation Manager has activated ${vacationMode} mode since everyone has been gone for more than ${vacationTime} hours"
            log.info message
			send(message)
			setLocationMode(vacationMode)
            state[onVacation()] = "true"
            location.helloHome?.execute(settings.vacationRoutine)
            // Turn off any devices that should be off during vacation
            if (offDevices) offDevices.off()
            if (offValves) offValves.close()
            // If we have a house sitter, start looking for their arrival/departure
            if (houseSitters) subscribe(houseSitters, "presence", houseSitterPresence)
		} else {
			log.debug "vacation manager determined not everyone has been away long enough; doing nothing"
            state[onVacation()] = "false"
		}
	} else {
    	log.debug "vacation manager determined not everyone is away; doing nothing"
        state[onVacation()] = "false"
    }
}

def houseSitterPresence(evt) {
	if (evt.value == "present") {
		send("Vacation Manager reports house sitter has arrived") 
		log.debug "house sitter has arrived during ${location.mode} mode"
		if (location.mode == vacationMode) {
			// Turn on any devices that should be on when the house sitter is present
            log.debug "turning on devices for house sitter arrival"
            if (onSitterDevices) onSitterDevices.on()
            if (onSitterVavles) onSitterValves.open()
            if (sitterArrivalRoutine) log.debug "executing routine '${settings.sitterArrivalRoutine}' for house sitter arrival"
            location.helloHome?.execute(settings.sitterArrivalRoutine)
            // Let SHM settle and then put us back into vacation mode if not already
			if (location.mode != vacationMode) setLocationMode(vacationMode)   
            runIn(5, setVacationMode)
		}
	}
	else if (evt.value == "not present") {
		send("Vacation Manager reports house sitter has departed") 
		log.debug "house sitter has departed during ${location.mode} mode"
        if (location.mode == vacationMode) {
            // Turn off any devices that should be off when the house sitter has left
            if (boolOffSitterDevicesLeave) {
            	log.debug "turning off devices that were turned on when house sitter arrived"
            	if (onSitterDevices) onSitterDevices.off()
                if (onSitterVavles) onSitterValves.close()
            }
            log.debug "turning off selected devices after sitter has left"
            if (offSitterDevices) offSitterDevices.off()
            if (offSitterVavles) offSitterValves.close()
            if (sitterDepartureRoutine) log.debug "executing routine '${settings.sitterDepartureRoutine}' for house sitter departure"
            location.helloHome?.execute(settings.sitterDepartureRoutine)
			// Set the mode back to the vacation mode just in case the mode or some devices changed
			if (vacationRoutine) log.debug "executing routine '${settings.vacationRoutine}' for house sitter departure"
			location.helloHome?.execute(settings.vacationRoutine)
            if (location.mode != vacationMode) setLocationMode(vacationMode)
            runIn(5, setVacationMode)
            }
    }
}

private everyoneIsAway() {
	def result = true
	for (person in people) {
		if (person.currentPresence == "present") {
			result = false
			break
		}
	}
	return result
}

private send(msg) {
	if (location.contactBookEnabled) {
        log.debug("sending notifications to: ${recipients?.size()}")
		sendNotificationToContacts(msg, recipients)
	}
	else  {
		if (sendPushMessage != "No") {
			log.debug("sending push message")
			sendPush(msg)
		}

		if (phone) {
			log.debug("sending text message")
			sendSms(phone, msg)
		}
	}
	log.debug msg
}

private findVacationThreshold() {
	(vacationTime != null && vacationTime != "") ? vacationTime : 24
}

def setVacationMode() {
	if (location.mode != vacationMode) {
    	log.debug "resetting current mode to ${vacationMode}"
        setLocationMode(vacationMode)
    }
}

private onVacation() {
	"onVacation"
}