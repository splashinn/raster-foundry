/* globals _ */
export default class DateRangePickerModalController {
    constructor(moment, dateRangePickerConf) {
        'ngInject';
        this.Moment = moment;
        this.dateRangePickerConf = dateRangePickerConf;
    }

    $onInit() {
        this.pickerApi = {};
        this.range = this.range || this.resolve.config.range || {};
        this._range = {
            start: this.range.start || this.Moment(),
            end: this.range.end || this.Moment()
        };
        this.ranges = this.resolve.config.ranges || [];
        this.minDay = this.resolve.config.minDay;
        this.maxDay = this.resolve.config.maxDay;
    }

    isActivePreset(range, index) {
        return this.selectedRangeIndex === index && this.matchesSelectedRange(range);
    }

    matchesSelectedRange(range) {
        if (_.isEmpty(range.start) || _.isEmpty(range.end)) {
            return true;
        }
        return range.start.isSame(this._range.start) && range.end.isSame(this._range.end);
    }

    onPresetSelect(range, index) {
        if (!_.isEmpty(range.start) && !_.isEmpty(range.end)) {
            this._range.start = range.start;
            this._range.end = range.end;
            this.isRangeEmpty = false;
        } else {
            this.isRangeEmpty = true;
        }
        this.selectedRangeIndex = index;
    }

    getSelectedPreset() {
        if (this.selectedRangeIndex) {
            let selectedRange = this.ranges[this.selectedRangeIndex];
            if (this.matchesSelectedRange(selectedRange)) {
                return selectedRange;
            }
        }
        return false;
    }

    cancel() {
        this.closeWithData(false);
    }

    apply() {
        let data = {
            start: this.isRangeEmpty ? {} : this._range.start,
            end: this.isRangeEmpty ? {} : this._range.end
        };
        const selectedRange = this.getSelectedPreset();
        if (selectedRange) {
            data.preset = selectedRange.name;
        }
        this.closeWithData(data);
    }

    closeWithData(data) {
        this.close({$value: data});
    }
}
