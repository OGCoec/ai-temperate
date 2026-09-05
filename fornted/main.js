import App from './App'
import authPageMixin from './common/auth/auth-page-mixin.js'
import { installAuthenticatedNavigationGuard } from './common/auth/navigation-guard.js'
import UniIcons from '@/uni_modules/uni-icons/components/uni-icons/uni-icons.vue'
import UniPopup from '@/uni_modules/uni-popup/components/uni-popup/uni-popup.vue'
import UniSearchBar from '@/uni_modules/uni-search-bar/components/uni-search-bar/uni-search-bar.vue'
import UniTransition from '@/uni_modules/uni-transition/components/uni-transition/uni-transition.vue'
import UniDatetimePicker from '@/uni_modules/uni-datetime-picker/components/uni-datetime-picker/uni-datetime-picker.vue'

installAuthenticatedNavigationGuard()

// #ifndef VUE3
import Vue from 'vue'
Vue.config.productionTip = false
Vue.component('uni-icons', UniIcons)
Vue.component('uni-popup', UniPopup)
Vue.component('uni-search-bar', UniSearchBar)
Vue.component('uni-transition', UniTransition)
Vue.component('uni-datetime-picker', UniDatetimePicker)
Vue.mixin(authPageMixin)
App.mpType = 'app'
const app = new Vue({
	...App
})
app.$mount()
// #endif

// #ifdef VUE3
import {
	createSSRApp
} from 'vue'
export function createApp() {
	const app = createSSRApp(App)
	app.component('uni-icons', UniIcons)
	app.component('uni-popup', UniPopup)
	app.component('uni-search-bar', UniSearchBar)
	app.component('uni-transition', UniTransition)
	app.component('uni-datetime-picker', UniDatetimePicker)
	app.mixin(authPageMixin)
	return {
		app
	}
}
// #endif
