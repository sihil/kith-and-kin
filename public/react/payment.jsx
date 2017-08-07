import React from 'react';
import ReactDOM from 'react-dom';

class Payment extends React.Component {
    constructor() {
        super();
        this.state = {
            valid: false
        }
        this.handleChange = this.handleChange.bind(this);
    };

    isFloat(val) {
        const floatRegex = /^-?\d+(?:[.,]\d*?)?$/;
        if (!floatRegex.test(val))
            return false;

        val = parseFloat(val);
        return !isNaN(val);
    }

    handleChange(event) {
        const text = event.target.value;
        const valid = this.isFloat(text);
        // update valid state
        this.setState({valid: valid});
    };

    render() {
        const disabled = !this.state.valid;
        return (
            <div className="notification payment">
                <div>Contribute £<input className="input is-inline padding-below" type="text" name="amount_sterling" onChange={this.handleChange}/> by:</div>
                <button className="button is-large is-info" name="method" value="transfer" disabled={disabled}>bank transfer</button>
                <button className="button is-large is-info" name="method" value="card" disabled={disabled}>card</button>
            </div>
        );
    };
}

ReactDOM.render(
    <Payment />,
    document.getElementById('anyAmount')
);